"""Storm-test helpers that run inside the snmp-systest container (#43, plan §6.4).

  python storm_box.py send <plan.json> <result.json>
      Sends pre-encoded v2c Traps (and v2c Informs, counting their ACKs) from the source IPs in the plan.
      net-snmp's snmptrap forks one process per packet and tops out far below 2000/s, so packets are
      BER-encoded once and written straight to UDP sockets.

  python storm_box.py probe <seconds> <out.jsonl> <nodeid> [<nodeid> ...] | @<file>
      Subscribes (1 s sampling) to the given SNMP items, which makes the Gateway poll them, and writes one
      JSON line per data change: node, status, sourceTs, serverTs, clientTs (epoch seconds).
"""
import asyncio
import json
import select
import socket
import sys
import time

SYS_UP_TIME = "1.3.6.1.2.1.1.3.0"
SNMP_TRAP_OID = "1.3.6.1.6.3.1.1.4.1.0"
LINK_DOWN = "1.3.6.1.6.3.1.1.5.3"
IF_INDEX_1 = "1.3.6.1.2.1.2.2.1.1.1"

# ---------------------------------------------------------------- BER


def tlv(tag, body):
    n = len(body)
    if n < 0x80:
        head = bytes([n])
    else:
        raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
        head = bytes([0x80 | len(raw)]) + raw
    return bytes([tag]) + head + body


def ber_int(v, tag=0x02):
    raw = v.to_bytes(max(1, (v.bit_length() + 8) // 8), "big", signed=True)
    return tlv(tag, raw)


def ber_oid(dotted):
    parts = [int(p) for p in dotted.split(".")]
    out = bytearray([parts[0] * 40 + parts[1]])
    for p in parts[2:]:
        chunk = [p & 0x7F]
        p >>= 7
        while p:
            chunk.append(0x80 | (p & 0x7F))
            p >>= 7
        out += bytes(reversed(chunk))
    return tlv(0x06, bytes(out))


def notification(community, request_id, inform=False, uptime=0):
    varbinds = tlv(0x30, b"".join([
        tlv(0x30, ber_oid(SYS_UP_TIME) + ber_int(uptime, 0x43)),
        tlv(0x30, ber_oid(SNMP_TRAP_OID) + ber_oid(LINK_DOWN)),
        tlv(0x30, ber_oid(IF_INDEX_1) + ber_int(1)),
    ]))
    pdu = tlv(0xA6 if inform else 0xA7, ber_int(request_id) + ber_int(0) + ber_int(0) + varbinds)
    return tlv(0x30, ber_int(1) + tlv(0x04, community.encode()) + pdu)


def read_tlv(buf, i):
    tag = buf[i]
    n = buf[i + 1]
    i += 2
    if n & 0x80:
        k = n & 0x7F
        n = int.from_bytes(buf[i:i + k], "big")
        i += k
    return tag, buf[i:i + n], i + n


def response_request_id(buf):
    """Request ID of a Response PDU, else None."""
    try:
        _, msg, _ = read_tlv(buf, 0)
        _, _, i = read_tlv(msg, 0)  # version
        _, _, i = read_tlv(msg, i)  # community
        tag, pdu, _ = read_tlv(msg, i)
        if tag != 0xA2:
            return None
        _, rid, _ = read_tlv(pdu, 0)
        return int.from_bytes(rid, "big", signed=True)
    except (IndexError, ValueError):
        return None

# ---------------------------------------------------------------- sender


def send(plan_path, result_path):
    plan = json.loads(open(plan_path).read())
    target = (plan["gw"], plan["port"])
    trap_socks = []
    for ip in plan["trapSources"]:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 << 20)
        s.bind((ip, 0))
        s.connect(target)
        trap_socks.append(s)
    inform_socks = []
    for ip in plan["informSources"]:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind((ip, 0))
        s.connect(target)
        s.setblocking(False)
        inform_socks.append(s)
    trap = notification(plan["community"], 1)
    trap_rate = plan["trapRate"]
    inform_rate = plan["informRate"] * len(inform_socks)  # informRate is per source
    seconds = plan["seconds"]
    pending = {}  # request id -> source index
    acked = 0
    sent = informs = errors = 0
    rid = 100_000
    t0 = time.monotonic()
    wall0 = time.time()
    while True:
        elapsed = time.monotonic() - t0
        if elapsed >= seconds:
            break
        due = int(trap_rate * elapsed) - sent
        for _ in range(min(due, 2000)):
            try:
                trap_socks[sent % len(trap_socks)].send(trap)
            except OSError:
                errors += 1
            sent += 1
        due_inf = int(inform_rate * elapsed) - informs if inform_socks else 0
        for _ in range(due_inf):
            rid += 1
            idx = informs % len(inform_socks)
            try:
                inform_socks[idx].send(notification(plan["community"], rid, inform=True))
                pending[rid] = idx
            except OSError:
                errors += 1
            informs += 1
        acked += drain(inform_socks, pending)
        if due < 2000:
            time.sleep(0.0005)
    duration = time.monotonic() - t0
    deadline = time.monotonic() + 5  # late ACKs
    while pending and time.monotonic() < deadline:
        acked += drain(inform_socks, pending, timeout=0.2)
    result = {"phase": plan.get("name"), "start": wall0, "end": wall0 + duration, "seconds": round(duration, 3),
              "trapsSent": sent, "trapRateAchieved": round(sent / duration, 1), "informsSent": informs,
              "informsAcked": acked, "informsNotAcked": len(pending), "sendErrors": errors,
              "notAckedBySource": {}}
    for idx in pending.values():
        ip = plan["informSources"][idx]
        result["notAckedBySource"][ip] = result["notAckedBySource"].get(ip, 0) + 1
    open(result_path, "w").write(json.dumps(result))
    print(json.dumps(result))


def drain(socks, pending, timeout=0.0):
    if not socks:
        return 0
    n = 0
    ready, _, _ = select.select(socks, [], [], timeout)
    for s in ready:
        while True:
            try:
                buf = s.recv(65535)
            except BlockingIOError:
                break
            rid = response_request_id(buf)
            if rid is not None and pending.pop(rid, None) is not None:
                n += 1
    return n

# ---------------------------------------------------------------- probe


async def probe(seconds, out_path, nids):
    from asyncua import ua
    from opc import connect

    out = open(out_path, "w", buffering=1 << 16)

    def ts(dt):
        return dt.timestamp() if dt else None

    class Handler:
        def datachange_notification(self, node, val, data):
            dv = data.monitored_item.Value
            out.write(json.dumps({"node": node.nodeid.to_string(), "status": dv.StatusCode.name,
                                  "sourceTs": ts(dv.SourceTimestamp), "serverTs": ts(dv.ServerTimestamp),
                                  "clientTs": time.time()}) + "\n")

    c = await connect()
    try:
        sub = await c.create_subscription(ua.CreateSubscriptionParameters(
            RequestedPublishingInterval=200, RequestedLifetimeCount=600, RequestedMaxKeepAliveCount=20,
            MaxNotificationsPerPublish=0, PublishingEnabled=True, Priority=0), Handler())
        for i in range(0, len(nids), 250):
            await sub.subscribe_data_change([c.get_node(n) for n in nids[i:i + 250]], sampling_interval=1000)
        await asyncio.sleep(seconds)
        await sub.delete()
    finally:
        out.close()
        await c.disconnect()


if __name__ == "__main__":
    if sys.argv[1] == "send":
        send(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "probe":
        nids = sys.argv[4:]
        if len(nids) == 1 and nids[0].startswith("@"):
            nids = open(nids[0][1:]).read().split()
        asyncio.run(probe(float(sys.argv[2]), sys.argv[3], nids))
    else:
        print(__doc__)
        sys.exit(2)
