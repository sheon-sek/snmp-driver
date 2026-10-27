#!/usr/bin/env python3
"""M1d system-test driver.

Runs on the WSL host from the repo root:

    python3 system-test/st.py setup            # build + start the systest container, trust its OPC cert
    python3 system-test/st.py run [case ...]   # run all cases, or the named ones
    python3 system-test/st.py list

Devices are created through the Gateway REST API (igdev gateway api). Notifications
are sent from the systest container (net-snmp) on the igdev Docker network, and
results are asserted by reading the device's Traps/* OPC UA nodes. No credentials
are written to disk; the Gateway admin password is read from `igdev gateway
credentials` at runtime and passed to the container through the environment.
"""
import json
import os
import re
import subprocess
import sys
import time
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "out"
CONTAINER = "snmp-systest"
IMAGE = "snmp-driver-systest:1"
DEVICE_TYPE = "com.jw.snmpdriver.snmp-device"
DEV_RES = "/data/api/v1/resources/com.inductiveautomation.opcua/device"
TRAP_PORT = 10162

# ---------------------------------------------------------------- shell / igdev


def run(cmd, check=True, input=None, env=None, timeout=300):
    p = subprocess.run(cmd, capture_output=True, text=True, input=input, env=env, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"{cmd[:4]} exit {p.returncode}: {p.stderr.strip()[-800:]}")
    return p


def igdev(*args, check=True):
    p = run(["igdev", *args, "--json"], check=False)
    try:
        env = json.loads(p.stdout)
    except json.JSONDecodeError:
        raise RuntimeError(f"igdev {args[:3]}: no JSON envelope: {p.stderr[-500:]}")
    if check and not env.get("ok"):
        raise RuntimeError(f"igdev {args[:3]}: {env.get('code')} {env.get('message')}")
    return env


def api(method, path, data=None, check=True):
    args = ["gateway", "api", method, path]
    if data is not None:
        if isinstance(data, str):
            args += ["--header", "Content-Type: text/plain", "--data", data]
        else:
            tmp = OUT / ".req.json"
            tmp.write_text(json.dumps(data))
            args += ["--data", "@" + str(tmp)]
    env = igdev(*args, check=False)
    body = env.get("data", {}).get("body")
    if check and not env.get("ok"):
        raise RuntimeError(f"{method} {path}: {env.get('message')} {json.dumps(body)[:600]}")
    return env.get("ok"), body


_gw_info = {}


def gateway_info():
    if not _gw_info:
        ctx = igdev("agent", "context")["data"]
        ns = ctx["instance"]["namespace"] if ctx.get("instance") else None
        if not ns:
            raise RuntimeError("no igdev instance; run igdev gateway ensure")
        _gw_info["ns"] = ns
        _gw_info["container"] = f"{ns}-gateway-1"
        _gw_info["network"] = f"{ns}_default"
        cred = igdev("gateway", "credentials")["data"]
        _gw_info["user"], _gw_info["pass"] = cred["username"], cred["password"]
    return _gw_info


def container_ip(name):
    return run(["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                name]).stdout.strip()


def sx(cmd, check=True, timeout=60):
    """Run a shell command in the systest container."""
    return run(["docker", "exec", CONTAINER, "sh", "-c", cmd], check=check, timeout=timeout)


# ---------------------------------------------------------------- OPC UA


def opc(*args):
    gi = gateway_info()
    env = dict(os.environ, IG_USER=gi["user"], IG_PASS=gi["pass"])
    p = run(["docker", "exec", "-e", "IG_USER", "-e", "IG_PASS", CONTAINER, "python", "/work/opc.py", *args],
            env=env, check=False, timeout=60)
    if p.returncode != 0:
        raise RuntimeError("opc.py failed: " + p.stderr.strip()[-600:])
    return p.stdout


def read(device, *names):
    """Read Traps/<name> (or any device-relative path) values -> dict name->value."""
    # Traps-relative by default; "Diagnostics/..." or "@<item path>" address other device nodes.
    ids = [f"ns=1;s=[{device}]" + (n[1:] if n.startswith("@") else n if n.startswith("Diagnostics/")
                                   else "Traps/" + n) for n in names]
    line = [ln for ln in opc("read", *ids).splitlines() if ln.startswith("{")][-1]
    vals = json.loads(line)
    return {n: vals[i] for n, i in zip(names, ids)}


def wait_for(fn, timeout=15.0, interval=0.5, what="condition"):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = fn()
            if last:
                return last
        except Exception as e:  # noqa: BLE001 - transient while devices restart
            last = e
        time.sleep(interval)
    raise AssertionError(f"timeout waiting for {what}; last={last!r}")


# ---------------------------------------------------------------- Record Delivery project fixture

RECORD_PROJECT = "st-records"
RECORD_FUNCTION = "snmp.handlers.onNotification"
RECORD_FILE = "/tmp/snmp-driver-st-records.jsonl"
RECORD_CONTROL = "/tmp/snmp-driver-st-records-control.json"


def import_record_project(overwrite=False, version="v1", enabled=True, inheritable=False):
    """Import the isolated test project; overwriting is always explicit.

    version="v2" changes the actual Script Library source for hot-update tests.
    Neither this function nor a script import clears collected records.
    """
    if version not in ("v1", "v2"):
        raise ValueError("record fixture version must be v1 or v2")
    source = ROOT / "projects" / RECORD_PROJECT
    if version != "v1" or not enabled or inheritable:
        target = OUT / f"record-project-{version}-{enabled}-{inheritable}"
        for path in source.rglob("*"):
            if path.is_file():
                dest = target / path.relative_to(source)
                dest.parent.mkdir(parents=True, exist_ok=True)
                text = path.read_text()
                if path.name == "code.py":
                    text = text.replace('VERSION = "v1"', f'VERSION = "{version}"')
                elif path.name == "project.json":
                    manifest = json.loads(text)
                    manifest.update(enabled=enabled, inheritable=inheritable)
                    text = json.dumps(manifest)
                dest.write_text(text)
        source = target
    args = ["project", "import", str(source), "--name", RECORD_PROJECT]
    if overwrite:
        args.append("--overwrite")
    return igdev(*args)


def set_record_handler(mode="normal", delay_millis=6000, version=None):
    """Select normal/exception/slow behavior without importing a project.

    version optionally overrides the label for assertions; use
    import_record_project(version="v2", overwrite=True) to test a real script save.
    An already executing handler keeps its captured configuration.
    """
    if mode not in ("normal", "exception", "slow"):
        raise ValueError("record handler mode must be normal, exception or slow")
    if not isinstance(delay_millis, int) or delay_millis < 0:
        raise ValueError("delay_millis must be a nonnegative integer")
    config = {"mode": mode, "delayMillis": delay_millis}
    if version is not None:
        config["version"] = str(version)
    # Atomic rename means a concurrent handler sees either complete config.
    run(["docker", "exec", "-i", gateway_info()["container"], "sh", "-c",
         "cat > /tmp/snmp-driver-st-records-control.next && "
         "mv /tmp/snmp-driver-st-records-control.next /tmp/snmp-driver-st-records-control.json"],
        input=json.dumps(config))


def read_records(with_metadata=False):
    """Read complete sink lines in append order; default returns decoded records.

    with_metadata=True returns {"version": ..., "record": <raw JSON string>}.
    A last line still being written is ignored until the next read.
    """
    p = run(["docker", "exec", gateway_info()["container"], "sh", "-c",
             "if test -f /tmp/snmp-driver-st-records.jsonl; then "
             "cat /tmp/snmp-driver-st-records.jsonl; fi"])
    entries = [json.loads(line) for line in p.stdout.splitlines(keepends=True)
               if line.endswith("\n")]
    return entries if with_metadata else [json.loads(entry["record"]) for entry in entries]


def clear_records():
    """Truncate only the test sink; stop senders and drain handlers before calling."""
    run(["docker", "exec", gateway_info()["container"], "truncate", "-s", "0", RECORD_FILE])


# ---------------------------------------------------------------- devices

DEFAULTS = {
    "host": "", "port": 161, "ipFamily": "AUTO", "version": "V3",
    "readCommunity": None, "writeCommunity": None, "securityName": "",
    "securityLevel": "AUTH_PRIV", "authProtocol": "SHA256", "authPassphrase": None,
    "privacyProtocol": "AES128", "privacyPassphrase": None, "contextName": "",
    "accessMode": "READ_ONLY", "timeoutMillis": 2000, "retries": 1,
    "maxOidsPerRequest": 25, "maxRequestBytes": 1200, "maxInflightPerDevice": 2,
    "trapEnabled": False, "trapBindAddress": "0.0.0.0", "trapPort": 162,
    "trapSecurity": "COMMUNITY", "trapCommunity": None, "trapSenderEngineId": "",
    "trapAcceptInform": True, "trapSourceAllowlist": "", "trapRateLimitPerSecond": 100,
}
SECRET_FIELDS = {"readCommunity", "writeCommunity", "authPassphrase", "privacyPassphrase", "trapCommunity"}


def secret(plain):
    _, body = api("POST", "/data/api/v1/encryption/encrypt", plain)
    return {"type": "Embedded", "data": body}


def device_settings(**over):
    s = dict(DEFAULTS)
    s.update(over)
    for k in SECRET_FIELDS:
        if isinstance(s[k], str):
            s[k] = secret(s[k])
    return s


def device_names():
    _, body = api("GET", "/data/api/v1/resources/names/com.inductiveautomation.opcua/device")
    return [i["name"] for i in body.get("items", [])]


def get_device(name):
    _, body = api("GET", f"/data/api/v1/resources/find/com.inductiveautomation.opcua/device/{name}")
    return body


def create_device(name, enabled=True, check=True, **over):
    if name in device_names():
        delete_device(name)
    res = [{"name": name, "enabled": enabled,
            "config": {"profile": {"type": DEVICE_TYPE}, "settings": device_settings(**over)}}]
    ok, body = api("POST", DEV_RES, res, check=check)
    return ok, body


def update_device(name, enabled=None, check=True, **over):
    cur = get_device(name)
    settings = cur["config"]["settings"]
    for k, v in over.items():
        settings[k] = secret(v) if k in SECRET_FIELDS and isinstance(v, str) else v
    res = [{"name": name, "signature": cur["signature"], "collection": cur.get("collection", "core"),
            "enabled": cur["enabled"] if enabled is None else enabled,
            "config": {"profile": cur["config"]["profile"], "settings": settings}}]
    return api("PUT", DEV_RES, res, check=check)


def delete_device(name):
    cur = get_device(name)
    api("POST", "/data/api/v1/resources/delete/com.inductiveautomation.opcua/device?confirm=true",
        [{"name": name, "signature": cur["signature"], "collection": cur.get("collection", "core")}])


def delete_all_devices(prefix="st-"):
    for n in device_names():
        if n.startswith(prefix):
            delete_device(n)


def wait_status(device, status, timeout=20):
    return wait_for(lambda: read(device, "Status")["Status"] == status, timeout, what=f"{device} Status={status}")


# ---------------------------------------------------------------- senders


def gw_ip():
    return container_ip(gateway_info()["container"])


def send(cmd, check=True):
    """net-snmp command run in the systest container; '{gw}' expands to gateway:<trapPort>."""
    return sx(cmd.replace("{gw}", f"gateway:{TRAP_PORT}"), check=check)


def hex_blocks(text):
    """net-snmp -d dumps -> [("Sending"|"Received", hex)] in order."""
    out = []
    for kind, n, dump in re.findall(r"(Sending|Received) (\d+) by[^\n]*\n((?:\s*[0-9A-F]{4}: [^\n]*\n)+)", text):
        hexs = "".join(ln.split(": ", 1)[1].split("    ")[0].replace(" ", "") for ln in dump.splitlines() if ": " in ln)
        out.append((kind, hexs[: int(n) * 2].lower()))
    return out


def inform_bytes(cmd):
    """Run an snmpinform with -d and return the last PDU it sent (hex), for replay."""
    out = send(cmd.replace("snmpinform ", "snmpinform -d ", 1), check=False)
    sent = [h for k, h in hex_blocks(out.stdout + out.stderr) if k == "Sending"]
    if not sent:
        raise RuntimeError("no Sending block in snmpinform -d output:\n" + (out.stdout + out.stderr)[-800:])
    return sent[-1]


def replay(hexpdu, times=2, sport=40000):
    """Send the same UDP payload `times` times from one source port; returns response count."""
    py = ("import socket,sys;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);"
          f"s.bind(('',{sport}));s.settimeout(2);d=bytes.fromhex('{hexpdu}');n=0\n"
          f"for _ in range({times}):\n"
          "  s.sendto(d,('gateway',%d))\n"
          "  try:\n    s.recv(65535);n+=1\n  except socket.timeout:\n    pass\n"
          "print(n)") % TRAP_PORT
    return int(sx(f"python -c \"{py}\"").stdout.strip())


def poll(device, item, seconds=5):
    """Subscribe to an SNMP item (which starts polling) and return its last value."""
    nid = f"ns=1;s=[{device}]{item}"
    line = [ln for ln in opc("poll", str(seconds), nid).splitlines() if ln.startswith("{")][-1]
    return json.loads(line)[nid]


ENGINE_FILE = "/usr/local/bin/ignition/data/config/local/com.jw.snmpdriver/trap-engine.properties"


def engine_file():
    """trap-engine.properties as a dict (engineId, engineBoots, machineFingerprint)."""
    text = run(["docker", "exec", gateway_info()["container"], "cat", ENGINE_FILE]).stdout
    return dict(ln.split("=", 1) for ln in text.splitlines() if "=" in ln and not ln.startswith("#"))


def restart_gateway():
    igdev("restart")
    igdev("gateway", "ensure")
    wait_for(lambda: opc("browse", "", "0") is not None, 120, 3, "OPC UA after restart")


def start_agent():
    """snmpd in the systest container, for the polling checks."""
    if sx("pgrep -x snmpd", check=False).returncode != 0:
        run(["docker", "exec", "-d", CONTAINER, "snmpd", "-f", "-Lo", "-C", "-c", "/work/snmpd.conf"])
        time.sleep(1)


# ---------------------------------------------------------------- framework

CASES = []


def case(name):
    def deco(fn):
        CASES.append((name, fn))
        return fn
    return deco


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


def counters(device):
    return read(device, "ReceivedCount", "AcceptedCount", "AuthFailureCount", "DuplicateInformCount",
                "DroppedUnlicensedCount", "ListenerUnmatchedCount", "ListenerMultiMatchCount", "Last/Sequence")


def delta(before, after):
    return {k: after[k] - before[k] for k in before if isinstance(before[k], int)}


COMPOSE = ROOT.parent / ".igdev" / "runtime" / "compose.yaml"
PINNED_IDENTITY = ("    # M1d system test: fixed identity so a restart is not taken for a clone (ADR 0009).\n"
                   "    # igdev setup re-renders this file and drops it; st.py setup puts it back.\n"
                   "    hostname: snmp-gw\n"
                   "    mac_address: \"02:42:ac:12:00:62\"\n")


def recreate_gateway(compose_text):
    """Write the igdev compose file and recreate only the gateway container; the data volume stays."""
    COMPOSE.write_text(compose_text)
    gi = gateway_info()
    env = dict(os.environ, GATEWAY_ADMIN_USERNAME=gi["user"], GATEWAY_ADMIN_PASSWORD=gi["pass"])
    run(["docker", "compose", "-f", str(COMPOSE), "--env-file", str(COMPOSE.parent / "compose.env"),
         "up", "-d", "--no-build", "gateway"], env=env, timeout=600)
    igdev("gateway", "ensure")


def pin_gateway_identity():
    """Docker 25+ gives a container a new random MAC on every start, the only machine identifier inside it."""
    text = COMPOSE.read_text()
    if "mac_address:" in text:
        return False
    anchor = '    entrypoint: ["bash", "/usr/local/bin/igdev-gateway-entrypoint.sh"]\n'
    if text.count(anchor) != 1:
        raise RuntimeError("igdev compose layout changed; add hostname/mac_address to the gateway service by hand")
    recreate_gateway(text.replace(anchor, anchor + PINNED_IDENTITY))
    return True


def configure_opcua():
    """The OPC UA server listens on 0.0.0.0 (container network only, the port is not published) and
    authenticates against the user source that holds the igdev admin (IGDEV_SYSTEM_USER_SOURCE, default temp)."""
    _, res = api("GET", "/data/api/v1/resources/singleton/com.inductiveautomation.opcua/server-config")
    cfg = res["config"]
    want_bind, want_profile = ["0.0.0.0"], os.environ.get("IGDEV_SYSTEM_USER_SOURCE", "temp")
    if cfg["endpoint"]["bindAddresses"] == want_bind and cfg["authentication"]["authenticationProfile"] == want_profile:
        return False
    cfg["endpoint"]["bindAddresses"] = want_bind
    cfg["authentication"]["authenticationProfile"] = want_profile
    api("PUT", "/data/api/v1/resources/com.inductiveautomation.opcua/server-config",
        [{"signature": res["signature"], "config": cfg, "enabled": True, "collection": res.get("collection", "core")}])
    time.sleep(5)
    return True


def setup():
    OUT.mkdir(exist_ok=True)
    gi = gateway_info()
    if pin_gateway_identity():
        print("pinned gateway hostname/MAC (container recreated)")
    if configure_opcua():
        print("OPC UA server: bind 0.0.0.0, user source", os.environ.get("IGDEV_SYSTEM_USER_SOURCE", "temp"))
    run(["docker", "build", "-q", "-t", IMAGE, str(ROOT)], timeout=900)
    run(["docker", "rm", "-f", CONTAINER], check=False)
    run(["docker", "run", "-d", "--name", CONTAINER, "--hostname", CONTAINER, "--network", gi["network"],
         "--cap-add", "NET_ADMIN",  # storm.py adds per-Device IP aliases
         "-v", f"{ROOT}:/work", IMAGE])
    try:
        opc("browse", "", "0")
        trusted = True
    except RuntimeError:
        trusted = False  # opc.py created .pki/cert.der but the Gateway does not trust it yet
    if not trusted:
        for path in ("trusted", "rejected"):
            _, certs = api("GET", f"/data/opc-ua/api/v1/server/pki/certificates/{path}")
            for c in certs or []:
                if c.get("commonName") == CONTAINER:
                    api("DELETE", f"/data/opc-ua/api/v1/server/pki/certificates/{path}/{c['signature']}")
        tmp = OUT / ".client-cert.der"
        tmp.write_bytes((ROOT / ".pki" / "cert.der").read_bytes())
        igdev("gateway", "api", "POST", "/data/opc-ua/api/v1/server/pki/certificates/trusted",
                    "--header", "Content-Type: application/octet-stream", "--data", "@" + str(tmp))
        tmp.unlink()
        wait_for(lambda: opc("browse", "", "0") is not None, 20, 2, "OPC UA session")
    start_agent()
    print("setup ok: systest", container_ip(CONTAINER), "gateway", gw_ip())


def main(argv):
    OUT.mkdir(exist_ok=True)
    import st as lib  # the importable copy; cases.py registers into lib.CASES
    import cases  # noqa: F401
    CASES[:] = lib.CASES
    if not argv or argv[0] == "list":
        for n, _ in CASES:
            print(n)
        return 0
    if argv[0] == "setup":
        setup()
        return 0
    if argv[0] != "run":
        print(__doc__)
        return 2
    wanted = argv[1:]
    selected = [(n, f) for n, f in CASES if not wanted or any(n == w or n.startswith(w + ":") for w in wanted)]
    results = []
    for name, fn in selected:
        t0 = time.time()
        try:
            delete_all_devices()  # every case starts from a clean device set
            note = fn() or ""
            status = "PASS"
        except AssertionError as e:
            status, note = "FAIL", str(e)
        except Exception as e:  # noqa: BLE001
            status, note = "ERROR", f"{type(e).__name__}: {e}\n{traceback.format_exc(limit=3)}"
        dt = time.time() - t0
        print(f"{status:5} {name:45} {dt:6.1f}s  {str(note).splitlines()[0][:150] if note else ''}", flush=True)
        results.append({"case": name, "status": status, "seconds": round(dt, 1), "note": str(note)})
    stamp = time.strftime("%Y%m%dT%H%M%S")
    (OUT / f"results-{stamp}.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
    failed = [r for r in results if r["status"] != "PASS"]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed -> out/results-{stamp}.json")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.path.insert(0, str(ROOT))
    sys.exit(main(sys.argv[1:]))
