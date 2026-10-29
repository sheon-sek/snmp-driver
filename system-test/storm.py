#!/usr/bin/env python3
"""M1d storm test (#43, plan §6.4).

Runs on the WSL host from the repo root, after `python3 system-test/st.py setup`:

    python3 system-test/storm.py run [--baseline S] [--storm S] [--burst S] [--flood S] [--recovery S] [--handler 1]
    python3 system-test/storm.py analyze <out/storm-...>     # re-run the analysis of a finished run
    python3 system-test/storm.py cleanup                     # delete st-* devices, drop the IP aliases

Load: 50 v2c Devices x 25 OIDs, polled every second through one OPC UA subscription. Each Device has its own
IP alias on the systest container, so polling and Notifications are routed per Device. Devices 0-44 take
Traps, 45-49 take Informs (10/s each). Phases: baseline (no Notifications), storm (2000 trap/s), burst
(10000 trap/s), flood (120000 trap/s, beyond §6.4, to fill the shared queue), recovery. Everything is observed from outside the module: OPC UA values/timestamps and Traps/* counters,
a pcap of the SNMP GETs on the agent side, the Gateway's /proc/net/snmp UDP counters, and the heap figure
from the Gateway REST overview.

--handler 1 (#51) gives every Device the st-records Record Handler (system-test/RECORDS.md), --handler 2 its
no-op function (no sink file I/O, sinkLines stays 0); after the last phase the run waits for all Record queues to drain and checks
AcceptedCount = RecordDeliveredCount + RecordHandlerErrorCount + RecordDroppedCount + RecordQueueDepth.
"""
import bisect
import json
import statistics
import subprocess
import struct
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import st  # noqa: E402

DEVICES = 50
TRAP_DEVICES = 45
ALIAS = "172.18.250.{}"
COMM = "st-public"
OIDS = [
    "1.3.6.1.2.1.1.3.0", "1.3.6.1.2.1.1.1.0", "1.3.6.1.2.1.1.2.0", "1.3.6.1.2.1.1.4.0", "1.3.6.1.2.1.1.5.0",
    "1.3.6.1.2.1.1.6.0", "1.3.6.1.2.1.1.8.0", "1.3.6.1.2.1.25.1.1.0", "1.3.6.1.2.1.2.1.0",
    "1.3.6.1.2.1.2.2.1.2.1", "1.3.6.1.2.1.2.2.1.10.1", "1.3.6.1.2.1.2.2.1.16.1", "1.3.6.1.2.1.2.2.1.10.2",
    "1.3.6.1.2.1.2.2.1.16.2", "1.3.6.1.2.1.7.1.0", "1.3.6.1.2.1.7.4.0", "1.3.6.1.2.1.4.3.0",
    "1.3.6.1.2.1.4.10.0", "1.3.6.1.2.1.11.1.0", "1.3.6.1.2.1.11.2.0", "1.3.6.1.2.1.25.1.6.0",
    "1.3.6.1.2.1.25.2.2.0", "1.3.6.1.2.1.6.10.0", "1.3.6.1.2.1.6.11.0", "1.3.6.1.2.1.2.2.1.5.1",
]
LATENCY_OID = OIDS[0]  # sysUpTime changes every poll, so every poll produces a data change
COUNTERS = ["ReceivedCount", "AcceptedCount", "DroppedRateLimitCount", "DroppedQueueFullCount",
            "DroppedUnlicensedCount", "AuthFailureCount", "DuplicateInformCount", "ListenerUnmatchedCount",
            "ListenerMultiMatchCount", "RecordDeliveredCount", "RecordHandlerErrorCount", "RecordDroppedCount",
            "RecordQueueDepth"]
RECORD_COUNTS = ["RecordDeliveredCount", "RecordHandlerErrorCount", "RecordDroppedCount"]
HANDLERS = {1: st.RECORD_FUNCTION, 2: "snmp.handlers.onNotificationNoop"}


def name(i):
    return f"st-storm-{i:02d}"


def ip(i):
    return ALIAS.format(i + 1)

# ---------------------------------------------------------------- environment


def add_aliases():
    script = "; ".join(f"ip addr add {ip(i)}/16 dev eth0 2>/dev/null" for i in range(DEVICES))
    st.sx(script + "; true")


def drop_aliases():
    script = "; ".join(f"ip addr del {ip(i)}/16 dev eth0 2>/dev/null" for i in range(DEVICES))
    st.sx(script + "; true")


def create_devices(handler=0):
    st.delete_all_devices()
    enc = st.secret(COMM)
    res = []
    for i in range(DEVICES):
        s = dict(st.DEFAULTS, host=ip(i), version="V2C", trapEnabled=True, trapPort=st.TRAP_PORT,
                 trapRateLimitPerSecond=10_000)
        if handler:
            s.update(recordHandlerProject=st.RECORD_PROJECT, recordHandlerFunction=HANDLERS[handler])
        s["readCommunity"] = enc
        res.append({"name": name(i), "enabled": True,
                    "config": {"profile": {"type": st.DEVICE_TYPE}, "settings": s}})
    st.api("POST", st.DEV_RES, res)
    nids = [f"ns=1;s=[{name(i)}]Traps/Status" for i in range(DEVICES)]

    def all_listening():
        vals = json.loads([ln for ln in st.opc("read", *nids).splitlines() if ln.startswith("{")][-1])
        return all(v == "Listening" for v in vals.values())
    st.wait_for(all_listening, 90, 2, "50 storm Devices Listening")


def read_counters():
    nids = [f"ns=1;s=[{name(i)}]Traps/{c}" for i in range(DEVICES) for c in COUNTERS]
    vals = json.loads([ln for ln in st.opc("read", *nids).splitlines() if ln.startswith("{")][-1])
    return {name(i): {c: vals[f"ns=1;s=[{name(i)}]Traps/{c}"] for c in COUNTERS} for i in range(DEVICES)}


def gw_udp():
    text = st.run(["docker", "exec", st.gateway_info()["container"], "cat", "/proc/net/snmp"]).stdout
    rows = [ln.split()[1:] for ln in text.splitlines() if ln.startswith("Udp:")]
    return dict(zip(rows[0], map(int, rows[1])))


class Sampler(threading.Thread):
    """Gateway heap/CPU (REST overview) and UDP counters every `interval` seconds."""

    def __init__(self, path, interval=5):
        super().__init__(daemon=True)
        self.path, self.interval, self.stop = path, interval, threading.Event()

    def run(self):
        with open(self.path, "w") as out:
            while not self.stop.is_set():
                t = time.time()
                try:
                    _, body = st.api("GET", "/data/api/v1/overview")
                    row = {"t": t, "heapUsed": body["memory"][0], "heapMax": body["memory"][1],
                           "cpu": body["cpu"], "udp": gw_udp()}
                    out.write(json.dumps(row) + "\n")
                    out.flush()
                except Exception as e:  # noqa: BLE001 - keep sampling through a slow response
                    out.write(json.dumps({"t": t, "error": str(e)[:200]}) + "\n")
                self.stop.wait(self.interval)

# ---------------------------------------------------------------- run


def send_phase(outdir, phase, seconds, trap_rate, inform_rate, procs=1):
    """Traps from `procs` sender processes (one Python process tops out near 40000/s), Informs from one more."""
    traps = [ip(i) for i in range(TRAP_DEVICES)]
    plans = [{"trapSources": traps[k::procs], "informSources": [], "trapRate": trap_rate / procs,
              "informRate": 0} for k in range(procs)]
    plans.append({"trapSources": [], "informSources": [ip(i) for i in range(TRAP_DEVICES, DEVICES)],
                  "trapRate": 0, "informRate": inform_rate})
    running = []
    for k, p in enumerate(plans):
        p.update(name=f"{phase}-{k}", gw="gateway", port=st.TRAP_PORT, community=COMM, seconds=seconds)
        (outdir / f"plan-{phase}-{k}.json").write_text(json.dumps(p))
        running.append(subprocess.Popen(
            ["docker", "exec", st.CONTAINER, "python", "/work/storm_box.py", "send",
             f"/work/out/{outdir.name}/plan-{phase}-{k}.json", f"/work/out/{outdir.name}/send-{phase}-{k}.json"],
            stdout=subprocess.DEVNULL))
    for p in running:
        p.wait(timeout=seconds + 60)
    parts = [json.loads((outdir / f"send-{phase}-{k}.json").read_text()) for k in range(len(plans))]
    res = {"phase": phase, "start": min(p["start"] for p in parts), "end": max(p["end"] for p in parts),
           "senderProcesses": len(parts)}
    for key in ("trapsSent", "informsSent", "informsAcked", "informsNotAcked", "sendErrors"):
        res[key] = sum(p[key] for p in parts)
    res["seconds"] = round(res["end"] - res["start"], 3)
    res["trapRateAchieved"] = round(res["trapsSent"] / res["seconds"], 1)
    return res


def run(baseline=180, storm=300, burst=30, flood=10, recovery=60, inform_rate=10, handler=0):
    gi = st.gateway_info()
    outdir = st.OUT / time.strftime("storm-%Y%m%dT%H%M%S")
    outdir.mkdir(parents=True)
    rel = f"/work/out/{outdir.name}"
    st.start_agent()
    add_aliases()
    if handler:
        st.import_record_project(overwrite=True)
        st.set_record_handler("normal")
        st.clear_records()
    print("creating 50 Devices ...", flush=True)
    create_devices(handler)
    total = 20 + baseline + storm + burst + flood + recovery + 60
    nids = [f"ns=1;s=[{name(i)}]{oid}" for i in range(DEVICES) for oid in OIDS]
    (outdir / "nodes.txt").write_text("\n".join(nids))
    st.sx("pkill tcpdump; true")
    st.run(["docker", "exec", "-d", st.CONTAINER, "tcpdump", "-i", "eth0", "-n", "-U", "-Z", "root", "-s", "512",
            "-B", "16384",
            "-w", f"{rel}/snmp.pcap", "udp port 161"])
    st.run(["docker", "exec", "-d", "-e", f"IG_USER={gi['user']}", "-e", f"IG_PASS={gi['pass']}", st.CONTAINER,
            "sh", "-c", f"cd /work && python storm_box.py probe {total} {rel}/probe.jsonl @{rel}/nodes.txt"
                        f" > {rel}/probe.log 2>&1"])
    sampler = Sampler(outdir / "samples.jsonl")
    sampler.start()
    phases = []
    time.sleep(20)  # first polls and Ignition's subscription setup settle before the baseline window
    t = time.time()
    print(f"baseline {baseline}s", flush=True)
    time.sleep(baseline)
    phases.append({"phase": "baseline", "start": t, "end": time.time()})
    snaps = {"before": read_counters()}
    udp = {"before": gw_udp()}
    # flood is beyond §6.4: 120000 trap/s fills the shared queue, so Inform handling at queue-full is observable
    for phase, seconds, rate, procs in (("storm", storm, 2000, 1), ("burst", burst, 10_000, 1),
                                        ("flood", flood, 120_000, 3)):
        if not seconds:
            continue
        print(f"{phase} {seconds}s @ {rate} trap/s", flush=True)
        res = send_phase(outdir, phase, seconds, rate, inform_rate, procs)
        print("  sender:", json.dumps(res), flush=True)
        phases.append(res)
        time.sleep(4)  # counters are flushed once per second
        snaps[phase] = read_counters()
        udp[phase] = gw_udp()
    t = time.time()
    print(f"recovery {recovery}s", flush=True)
    time.sleep(recovery)
    phases.append({"phase": "recovery", "start": t, "end": time.time()})
    st.wait_for(lambda: st.sx("pgrep -f '[s]torm_box.py probe'", check=False).returncode != 0, 120, 2, "probe exit")
    st.sx("pkill tcpdump; true")
    sampler.stop.set()
    sampler.join()
    drained = None
    if handler:
        st.wait_for(lambda: sum(c["RecordQueueDepth"] for c in read_counters().values()) == 0, 1800, 5,
                    "Record queues drained")
        time.sleep(2)  # counters are flushed once per second
        snaps["drained"] = read_counters()
        drained = {"t": time.time(), "sinkLines": int(st.run(
            ["docker", "exec", gi["container"], "sh", "-c", f"wc -l < {st.RECORD_FILE}"]).stdout.split()[0])}
    diag = read_diagnostics()
    (outdir / "run.json").write_text(json.dumps({"phases": phases, "counters": snaps, "udp": udp,
                                                 "diagnostics": diag, "devices": DEVICES, "oids": len(OIDS),
                                                 "trapDevices": TRAP_DEVICES, "drained": drained}, indent=1))
    analyze(outdir)


def read_diagnostics():
    names = ["Diagnostics/Status", "Diagnostics/LastFailureTime", "Diagnostics/LastError",
             "Diagnostics/CircuitBreakerState"]
    nids = [f"ns=1;s=[{name(i)}]{n}" for i in range(DEVICES) for n in names]
    vals = json.loads([ln for ln in st.opc("read", *nids).splitlines() if ln.startswith("{")][-1])
    return {name(i): {n: vals[f"ns=1;s=[{name(i)}]{n}"] for n in names} for i in range(DEVICES)}

# ---------------------------------------------------------------- analysis


def read_tlv(buf, i):
    tag, n = buf[i], buf[i + 1]
    i += 2
    if n & 0x80:
        k = n & 0x7F
        n = int.from_bytes(buf[i:i + k], "big")
        i += k
    return tag, buf[i:i + n], i + n


UPTIME_OID_BER = bytes.fromhex("06082b06010201010300")


def pcap_snmp(path):
    """[(ts, src, dst, pduTag, requestId, carriesSysUpTime)] for SNMP packets in a pcap (Ethernet, IPv4/UDP)."""
    data = path.read_bytes()
    out = []
    i = 24
    while i + 16 <= len(data):
        sec, usec, incl, _ = struct.unpack("<IIII", data[i:i + 16])
        pkt = data[i + 16:i + 16 + incl]
        i += 16 + incl
        if len(pkt) < 42 or pkt[12:14] != b"\x08\x00":
            continue
        ihl = (pkt[14] & 0x0F) * 4
        src, dst = ".".join(map(str, pkt[26:30])), ".".join(map(str, pkt[30:34]))
        payload = pkt[14 + ihl + 8:]
        try:
            _, msg, _ = read_tlv(payload, 0)
            _, _, j = read_tlv(msg, 0)
            _, _, j = read_tlv(msg, j)
            tag, pdu, _ = read_tlv(msg, j)
            _, rid, _ = read_tlv(pdu, 0)
        except IndexError:
            continue
        out.append((sec + usec / 1e6, src, dst, tag, int.from_bytes(rid, "big", signed=True), UPTIME_OID_BER in pdu))
    return out


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


def summary(values):
    if not values:
        return {"n": 0}
    return {"n": len(values), "p50": round(pct(values, 50), 3), "p95": round(pct(values, 95), 3),
            "p99": round(pct(values, 99), 3), "max": round(max(values), 3), "mean": round(statistics.mean(values), 3)}


def analyze(outdir):
    """Poll latency = Gateway SourceTimestamp of sysUpTime.0 (set when the GET response reaches the module)
    minus the agent-side capture time of the GET request that carried sysUpTime.0. Same kernel clock."""
    outdir = Path(outdir)
    meta = json.loads((outdir / "run.json").read_text())
    phases = [(p["phase"], p["start"], p["end"]) for p in meta["phases"]]
    names = [ph for ph, _, _ in phases]
    gw = st.gw_ip()
    ip_to_dev = {ip(i): name(i) for i in range(DEVICES)}

    def phase_of(t):
        for ph, a, b in phases:
            if a <= t < b:
                return ph
        return None

    # GETs seen by the agent: a repeated (device, request id) is an SNMP retry, i.e. a timeout in the module
    cycles, requests, seen = {}, {ph: 0 for ph in names}, set()
    retries = {ph: 0 for ph in names}
    for t, src, dst, tag, rid, uptime in pcap_snmp(outdir / "snmp.pcap"):
        if tag != 0xA0 or src != gw or dst not in ip_to_dev:
            continue
        ph = phase_of(t)
        if (dst, rid) in seen:
            if ph:
                retries[ph] += 1
            continue
        seen.add((dst, rid))
        if ph:
            requests[ph] += 1
        if uptime:
            cycles.setdefault(ip_to_dev[dst], []).append(t)
    for v in cycles.values():
        v.sort()

    lat = {ph: [] for ph in names}
    interval = {ph: [] for ph in names}
    bad = {ph: {} for ph in names}
    for ts in cycles.values():
        for a, b in zip(ts, ts[1:]):
            ph = phase_of(b)
            if ph:
                interval[ph].append((b - a) * 1000)
    for line in (outdir / "probe.jsonl").read_text().splitlines():
        row = json.loads(line)
        ph = phase_of(row["clientTs"])
        if not ph:
            continue
        if row["status"] != "Good":
            bad[ph][row["status"]] = bad[ph].get(row["status"], 0) + 1
            continue
        if not row["node"].endswith("]" + LATENCY_OID) or not row["sourceTs"]:
            continue
        ts = cycles.get(row["node"].split("[", 1)[1].split("]", 1)[0], [])
        k = bisect.bisect_right(ts, row["sourceTs"]) - 1
        if k >= 0 and row["sourceTs"] - ts[k] < 5:
            lat[ph].append((row["sourceTs"] - ts[k]) * 1000)

    counters = meta["counters"]
    acct = {}
    prev = "before"
    trap_devs = [name(i) for i in range(TRAP_DEVICES)]
    inf_devs = [name(i) for i in range(TRAP_DEVICES, DEVICES)]
    drops = ["DroppedRateLimitCount", "DroppedQueueFullCount", "DroppedUnlicensedCount"]
    for res in meta["phases"]:
        ph = res["phase"]
        if ph not in counters:
            continue
        # .get: runs recorded before #51 have no Record counters
        d = {dev: {c: counters[ph][dev].get(c, 0) - counters[prev][dev].get(c, 0) for c in COUNTERS}
             for dev in counters[ph]}

        def total(devs, c):
            return sum(d[x][c] for x in devs)
        received = total(d, "ReceivedCount")
        accounted = total(d, "AcceptedCount") + sum(total(d, c) for c in drops + ["DuplicateInformCount"])
        u0, u1 = meta["udp"][prev], meta["udp"][ph]
        sent = res["trapsSent"] + res["informsSent"]
        reached = received + total(d, "AuthFailureCount") + d[name(0)]["ListenerUnmatchedCount"]
        acct[ph] = {
            "trapsSent": res["trapsSent"], "trapRateAchieved": res["trapRateAchieved"],
            "trapReceived": total(trap_devs, "ReceivedCount"), "trapAccepted": total(trap_devs, "AcceptedCount"),
            **{c: total(d, c) for c in drops + ["DuplicateInformCount", "AuthFailureCount"]},
            "listenerUnmatched": d[name(0)]["ListenerUnmatchedCount"],
            "received": received, "accountedInDevice": accounted, "unaccountedInDevice": received - accounted,
            "notReachingModule": sent - reached,
            "udpRcvbufErrors": u1["RcvbufErrors"] - u0["RcvbufErrors"],
            "udpInErrors": u1["InErrors"] - u0["InErrors"],
            "informsSent": res["informsSent"], "informsAcked": res["informsAcked"],
            "informsNotAcked": res["informsNotAcked"],
            "informReceived": total(inf_devs, "ReceivedCount"), "informAccepted": total(inf_devs, "AcceptedCount"),
            "informDropped": sum(total(inf_devs, c) for c in drops),
            "informDuplicate": total(inf_devs, "DuplicateInformCount"),
        }
        a = acct[ph]
        # every ACK belongs to a queued (or duplicate) Inform; every refused Inform went un-ACKed
        a["informAckMatchesAccepted"] = a["informsAcked"] == a["informAccepted"] + a["informDuplicate"]
        a["informNotAckedCovered"] = a["informsNotAcked"] >= a["informDropped"]
        prev = ph

    records = None
    if meta.get("drained"):
        end = counters["drained"]
        per_dev = {dev: v["AcceptedCount"] - sum(v[c] for c in RECORD_COUNTS) - v["RecordQueueDepth"]
                   for dev, v in end.items()}
        records = {"accepted": sum(v["AcceptedCount"] for v in end.values()),
                   **{c: sum(v[c] for v in end.values()) for c in RECORD_COUNTS + ["RecordQueueDepth"]},
                   "unreconciledDevices": {dev: n for dev, n in per_dev.items() if n},
                   "sinkLines": meta["drained"]["sinkLines"],
                   "queueDepthAfterEachPhase": {ph: sum(v["RecordQueueDepth"] for v in counters[ph].values())
                                                for ph in counters if ph not in ("before", "drained")},
                   "drainedSecondsAfterLastSend": round(
                       meta["drained"]["t"] - max(p["end"] for p in meta["phases"] if "trapsSent" in p), 1)}
        records["reconciles"] = not records["unreconciledDevices"] and records["accepted"] == sum(
            records[c] for c in RECORD_COUNTS + ["RecordQueueDepth"])

    heap, heap_by_phase = [], {}
    samples = [json.loads(ln) for ln in (outdir / "samples.jsonl").read_text().splitlines()]
    for row in samples:
        if "heapUsed" in row:
            ph = phase_of(row["t"])
            heap.append({"t": round(row["t"] - phases[0][1], 1), "phase": ph,
                         "heapMiB": round(row["heapUsed"] / 2**20), "cpu": round(row["cpu"], 3)})
            if ph:
                heap_by_phase.setdefault(ph, []).append(round(row["heapUsed"] / 2**20))

    base = summary(lat["baseline"]).get("p95")
    result = {"latencyMs": {ph: summary(v) for ph, v in lat.items()},
              "p95IncreasePct": {ph: round((pct(v, 95) / base - 1) * 100, 1) if base and v else None
                                 for ph, v in lat.items()},
              "cycleIntervalMs": {ph: summary(v) for ph, v in interval.items()},
              "cyclesPerDevicePerSecond": {ph: round(len(interval[ph]) / DEVICES / (b - a), 3)
                                           for ph, a, b in phases},
              "getRequests": requests, "getRetransmissions": retries, "badValues": bad,
              "accounting": acct, "records": records,
              "heapMiB": {ph: {"min": min(v), "max": max(v), "mean": round(statistics.mean(v))}
                          for ph, v in heap_by_phase.items()},
              "heapMaxMiB": round(next(r["heapMax"] for r in samples if "heapMax" in r) / 2**20),
              "lastErrors": sorted({v["Diagnostics/LastError"] for v in meta["diagnostics"].values()}),
              "breakers": sorted({v["Diagnostics/CircuitBreakerState"] for v in meta["diagnostics"].values()}),
              "phases": meta["phases"], "heapSeries": heap}
    (outdir / "summary.json").write_text(json.dumps(result, indent=1, ensure_ascii=False))
    print(json.dumps({k: v for k, v in result.items() if k not in ("heapSeries", "phases")}, indent=1,
                     ensure_ascii=False))
    return result


def cleanup():
    st.delete_all_devices()
    drop_aliases()
    print("removed st-* Devices and storm IP aliases")


def main(argv):
    st.OUT.mkdir(exist_ok=True)
    if not argv or argv[0] not in ("run", "analyze", "cleanup"):
        print(__doc__)
        return 2
    if argv[0] == "analyze":
        analyze(argv[1])
    elif argv[0] == "cleanup":
        cleanup()
    else:
        opts = dict(zip(argv[1::2], argv[2::2]))
        run(**{k.lstrip("-"): int(v) for k, v in opts.items()})
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
