"""System-test cases (plan §6.3). Each case leaves no st-* devices it does not need."""
import json
import time

from st import (hex_blocks, case, check, counters, create_device, delete_all_devices, delete_device, delta, inform_bytes,
                read, replay, send, sx, update_device, wait_for, wait_status, container_ip, CONTAINER, TRAP_PORT)

COMM = "st-public"
V2C_TRAP = "snmptrap -v 2c -c {c} {{gw}} '' 1.3.6.1.6.3.1.1.5.3 1.3.6.1.2.1.2.2.1.1.1 i 1"
V2C_INFORM = "snmpinform -r 0 -t 3 -v 2c -c {c} {{gw}} '' 1.3.6.1.6.3.1.1.5.4 1.3.6.1.2.1.2.2.1.1.1 i 1"
V1_TRAP = "snmptrap -v 1 -c {c} {{gw}} 1.3.6.1.4.1.8072.2.3 {agent} 6 17 '' 1.3.6.1.2.1.1.5.0 s test"


def me():
    return container_ip(CONTAINER)


def v2c_device(name="st-v2c", **over):
    kw = dict(host=me(), version="V2C", readCommunity=COMM, trapEnabled=True, trapPort=TRAP_PORT)
    kw.update(over)
    create_device(name, **kw)
    wait_status(name, "Listening")
    return name


def accepted_one(dev, before, **expect_last):
    def ok():
        d = delta(before, counters(dev))
        return d if d["AcceptedCount"] >= 1 else None
    d = wait_for(ok, 10, what=f"{dev} AcceptedCount+1")
    last = json.loads(read(dev, "Last/Json")["Last/Json"])
    for k, v in expect_last.items():
        check(last.get(k) == v, f"Last/Json.{k}={last.get(k)!r}, expected {v!r}")
    return d, last


# ---------------------------------------------------------------- v1 / v2c


@case("v2c:trap")
def v2c_trap():
    dev = v2c_device()
    b = counters(dev)
    send(V2C_TRAP.format(c=COMM))
    d, last = accepted_one(dev, b, pduType="V2_TRAP", version="V2C", trapOid="1.3.6.1.6.3.1.1.5.3")
    check(d["ReceivedCount"] == 1 and d["Last/Sequence"] == 1, f"delta {d}")
    check(last["source"].startswith(me() + ":"), f"source {last['source']}")


@case("v1:enterprise-specific")
def v1_trap():
    dev = v2c_device()
    b = counters(dev)
    send(V1_TRAP.format(c=COMM, agent="10.9.8.7"))
    # RFC 3584 §3.1: enterprise.0.specific
    _, last = accepted_one(dev, b, pduType="V1_TRAP", version="V1", trapOid="1.3.6.1.4.1.8072.2.3.0.17")
    check(last.get("agentAddress") == "10.9.8.7", f"agentAddress {last.get('agentAddress')}")
    oids = [v["oid"] for v in last["varbinds"]] if isinstance(last["varbinds"], list) else []
    check("1.3.6.1.2.1.1.5.0" in oids, f"varbinds {last['varbinds']}")


@case("v1:generic-linkdown")
def v1_generic():
    dev = v2c_device()
    b = counters(dev)
    send("snmptrap -v 1 -c {c} {gw} 1.3.6.1.4.1.8072.2.3 10.9.8.7 2 0 '' 1.3.6.1.2.1.2.2.1.1.1 i 1".format(
        c=COMM, gw="{gw}"))
    accepted_one(dev, b, pduType="V1_TRAP", trapOid="1.3.6.1.6.3.1.1.5.3")


@case("v2c:inform")
def v2c_inform():
    dev = v2c_device()
    b = counters(dev)
    out = send(V2C_INFORM.format(c=COMM), check=False)
    check(out.returncode == 0, f"snmpinform not ACKed: {out.stderr.strip()}")
    accepted_one(dev, b, pduType="INFORM", version="V2C")


@case("v2c:inform-duplicate")
def v2c_inform_dup():
    dev = v2c_device()
    pdu = inform_bytes(V2C_INFORM.format(c=COMM))
    time.sleep(1)
    b = counters(dev)
    acks = replay(pdu, times=3, sport=40001)
    time.sleep(1.5)
    d = delta(b, counters(dev))
    check(acks == 3, f"expected every copy ACKed, got {acks}")
    # replays come from a new source port, so the first copy is new; the dedup key includes source ip:port
    check(d["AcceptedCount"] == 1 and d["DuplicateInformCount"] == 2, f"delta {d}")


@case("v2c:inform-not-accepted")
def v2c_inform_disabled():
    dev = v2c_device(trapAcceptInform=False)
    b = counters(dev)
    out = send(V2C_INFORM.format(c=COMM), check=False)
    time.sleep(1)
    d = delta(b, counters(dev))
    check(out.returncode != 0, "Inform was ACKed although trapAcceptInform=false")
    check(d["AcceptedCount"] == 0, f"delta {d}")
    return f"delta {d}"


@case("v2c:wrong-community")
def v2c_wrong_community():
    dev = v2c_device()
    b = counters(dev)
    send(V2C_TRAP.format(c="wrong"))
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AuthFailureCount"] >= 1 and x, 10,
                 what="AuthFailureCount+1")
    check(d["AcceptedCount"] == 0, f"delta {d}")
    err = read(dev, "LastError")["LastError"]
    check(err != "", "LastError empty after auth failure")
    return f"LastError={err}"


@case("v2c:trap-community-field")
def v2c_trap_community():
    dev = v2c_device(trapCommunity="st-trap-only")
    b = counters(dev)
    send(V2C_TRAP.format(c=COMM))  # read community no longer valid for traps
    send(V2C_TRAP.format(c="st-trap-only"))
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AcceptedCount"] >= 1 and x["AuthFailureCount"] >= 1 and x,
                 10, what="1 accept + 1 auth failure")
    check(d["AcceptedCount"] == 1 and d["AuthFailureCount"] == 1, f"delta {d}")


@case("route:unmatched")
def route_unmatched():
    dev = v2c_device(host="10.255.255.1")  # not the sender
    b = counters(dev)
    send(V2C_TRAP.format(c=COMM))
    d = wait_for(lambda: (x := delta(b, counters(dev)))["ListenerUnmatchedCount"] >= 1 and x, 10,
                 what="ListenerUnmatchedCount+1")
    check(d["ReceivedCount"] == 0 and d["AcceptedCount"] == 0, f"delta {d}")


@case("route:allowlist")
def route_allowlist():
    dev = v2c_device(host="10.255.255.1", trapSourceAllowlist=me() + "/32")
    b = counters(dev)
    send(V2C_TRAP.format(c=COMM))
    accepted_one(dev, b)


@case("route:multi-match-broadcast")
def route_multi():
    a = v2c_device("st-a")
    bdev = v2c_device("st-b")
    ba, bb = counters(a), counters(bdev)
    send(V2C_TRAP.format(c=COMM))
    accepted_one(a, ba)
    accepted_one(bdev, bb)
    d = delta(ba, counters(a))
    check(d["ListenerMultiMatchCount"] >= 1, f"MultiMatch not counted: {d}")
    check(read(a, "ListenerEndpoint")["ListenerEndpoint"] == read(bdev, "ListenerEndpoint")["ListenerEndpoint"],
          "devices do not share the listener")
    delete_device(a)
    delete_device(bdev)


@case("listener:bind-conflict")
def listener_bind_conflict():
    # ADR 0007: cross-device conflicts are checked at runtime (registry.acquire), not rejected on save.
    v2c_device("st-a")
    gw = container_ip_gateway()
    ok, _ = create_device("st-b", check=False, host=me(), version="V2C", readCommunity=COMM, trapEnabled=True,
                          trapPort=TRAP_PORT, trapBindAddress=gw)
    check(ok, "save of the conflicting device was rejected; ADR 0007 expects runtime degradation")
    wait_status("st-b", "Degraded", 30)
    err = read("st-b", "LastError")["LastError"]
    check("st-a" in err and gw in err, f"LastError does not name the conflict: {err}")
    check(read("st-a", "Status")["Status"] == "Listening", "first device lost its listener")
    b = counters("st-a")
    send(V2C_TRAP.format(c=COMM))
    accepted_one("st-a", b)
    delete_all_devices()
    return f"LastError={err}"


def container_ip_gateway():
    from st import gw_ip
    return gw_ip()


@case("device:disable-enable")
def device_disable_enable():
    dev = v2c_device()
    update_device(dev, enabled=False)
    time.sleep(3)
    update_device(dev, enabled=True)
    wait_status(dev, "Listening", 30)
    b = counters(dev)
    send(V2C_TRAP.format(c=COMM))
    accepted_one(dev, b)


@case("device:trap-disabled")
def device_trap_disabled():
    dev = v2c_device()
    update_device(dev, trapEnabled=False)
    wait_status(dev, "Disabled", 30)
    delete_all_devices()


# ---------------------------------------------------------------- v3

V3_USER = "st-v3user"
AUTH_PW = "st-auth-pass-123"
PRIV_PW = "st-priv-pass-456"
SENDER_EID = "8000000001020304050607"
NS_AUTH = {"MD5": "MD5", "SHA1": "SHA", "SHA256": "SHA-256", "SHA512": "SHA-512"}
NS_PRIV = {"DES": "DES", "AES128": "AES", "AES256": "AES-256"}


def v3_device(name="st-v3", level="AUTH_PRIV", auth="SHA256", priv="AES128", **over):
    kw = dict(host=me(), version="V3", securityName=V3_USER, securityLevel=level, authProtocol=auth,
              privacyProtocol=priv, authPassphrase=AUTH_PW if level != "NO_AUTH_NO_PRIV" else None,
              privacyPassphrase=PRIV_PW if level == "AUTH_PRIV" else None, trapEnabled=True, trapPort=TRAP_PORT,
              trapSecurity="USM", trapSenderEngineId=SENDER_EID)
    kw.update(over)
    create_device(name, **kw)
    wait_status(name, "Listening", 30)
    return name


def v3_args(level, auth, priv, user=V3_USER, apw=AUTH_PW, ppw=PRIV_PW):
    a = f"-v 3 -u {user} -l {level[0].lower() + level.title().replace('_', '')[1:]}"
    a = a.replace("Noauthnopriv", "NoAuthNoPriv").replace("Authnopriv", "AuthNoPriv").replace("Authpriv", "AuthPriv")
    if level != "NO_AUTH_NO_PRIV":
        a += f" -a {NS_AUTH[auth]} -A {apw}"
    if level == "AUTH_PRIV":
        a += f" -x {NS_PRIV[priv]} -X {ppw}"
    return a


def v3_trap_cmd(args, eid=SENDER_EID):
    return f"snmptrap {args} -e {eid} {{gw}} '' 1.3.6.1.6.3.1.1.5.1"


def v3_inform_cmd(args):
    return f"snmpinform -r 0 -t 3 {args} {{gw}} '' 1.3.6.1.6.3.1.1.5.1"


V3_COMBOS = ([("NO_AUTH_NO_PRIV", "SHA256", "AES128")]
             + [("AUTH_NO_PRIV", a, "AES128") for a in NS_AUTH]
             + [("AUTH_PRIV", a, p) for a in NS_AUTH for p in NS_PRIV])


def _v3_combo(level, auth, priv):
    def fn():
        dev = v3_device(level=level, auth=auth, priv=priv)
        args = v3_args(level, auth, priv)
        b = counters(dev)
        send(v3_trap_cmd(args))
        accepted_one(dev, b, pduType="V2_TRAP", version="V3")
        b = counters(dev)
        out = send(v3_inform_cmd(args), check=False)
        check(out.returncode == 0, f"v3 inform not ACKed: {out.stderr.strip()[-300:]}")
        accepted_one(dev, b, pduType="INFORM", version="V3")
    return fn


for _l, _a, _p in V3_COMBOS:
    case(f"v3:{_l}:{_a}:{_p}")(_v3_combo(_l, _a, _p))


@case("v3:inform-engineid-matches-local")
def v3_engineid():
    dev = v3_device()
    local = read(dev, "LocalEngineId")["LocalEngineId"]
    out = send("snmpinform -d " + v3_inform_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128"))[len("snmpinform "):],
               check=False)
    received = [h for k, h in hex_blocks(out.stdout + out.stderr) if k == "Received"]
    check(local and len(local) >= 10, f"LocalEngineId={local!r}")
    check(received and local.lower() in received[0], f"LocalEngineId {local} not in the discovery Report")
    check(out.returncode == 0, "v3 inform not ACKed")
    return f"LocalEngineId={local}"


@case("v3:wrong-auth-pass")
def v3_wrong_auth():
    dev = v3_device()
    b = counters(dev)
    send(v3_trap_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128", apw="wrong-auth-pass")))
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AuthFailureCount"] >= 1 and x, 10, what="AuthFailure+1")
    check(d["AcceptedCount"] == 0, f"delta {d}")
    return "LastError=" + read(dev, "LastError")["LastError"]


@case("v3:wrong-priv-pass")
def v3_wrong_priv():
    dev = v3_device()
    b = counters(dev)
    send(v3_trap_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128", ppw="wrong-priv-pass")))
    time.sleep(2)
    d = delta(b, counters(dev))
    check(d["AcceptedCount"] == 0, f"accepted with wrong privacy passphrase: {d}")
    check(d["AuthFailureCount"] >= 1, f"AuthFailureCount not incremented: {d}")
    return "LastError=" + read(dev, "LastError")["LastError"]


@case("v3:wrong-engineid")
def v3_wrong_eid():
    dev = v3_device()
    b = counters(dev)
    send(v3_trap_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128"), eid="80000000010a0b0c0d0e0f"))
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AuthFailureCount"] >= 1 and x, 10, what="AuthFailure+1")
    check(d["AcceptedCount"] == 0, f"delta {d}")
    return "LastError=" + read(dev, "LastError")["LastError"]


@case("v3:wrong-user")
def v3_wrong_user():
    dev = v3_device()
    b = counters(dev)
    send(v3_trap_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128", user="nobody")))
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AuthFailureCount"] >= 1 and x, 10, what="AuthFailure+1")
    check(d["AcceptedCount"] == 0, f"delta {d}")
    return "LastError=" + read(dev, "LastError")["LastError"]


@case("v3:inform-duplicate")
def v3_inform_dup():
    dev = v3_device()
    pdu = inform_bytes(v3_inform_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128")))
    time.sleep(1)
    b = counters(dev)
    acks = replay(pdu, times=3, sport=40002)
    time.sleep(1.5)
    d = delta(b, counters(dev))
    check(acks == 3, f"expected every copy ACKed, got {acks}")
    # new source port: the first copy is new, the rest are duplicates (key includes source ip:port)
    check(d["AcceptedCount"] == 1 and d["DuplicateInformCount"] == 2, f"delta {d}")
    return f"delta {d}"


@case("v3:inform-wrong-priv-pass")
def v3_inform_wrong_priv():
    dev = v3_device()
    b = counters(dev)
    out = send(v3_inform_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128", ppw="wrong-priv-pass")), check=False)
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AuthFailureCount"] >= 1 and x, 10, what="AuthFailure+1")
    check(out.returncode != 0, "Inform with a wrong privacy passphrase was ACKed")
    check(d["AcceptedCount"] == 0, f"delta {d}")
    return "LastError=" + read(dev, "LastError")["LastError"]


@case("v3:sender-restart-boots")
def v3_sender_restart():
    """Trap: the sender is authoritative. A restart (boots+1, time reset) is accepted, a rollback is not."""
    dev = v3_device()
    args = v3_args("AUTH_PRIV", "SHA256", "AES128")
    trap = lambda z: send(f"snmptrap {args} -e {SENDER_EID} -Z {z} {{gw}} '' 1.3.6.1.6.3.1.1.5.1")  # noqa: E731
    b = counters(dev)
    trap("5,1000")
    accepted_one(dev, b)
    b = counters(dev)
    trap("6,10")  # sender restarted
    accepted_one(dev, b)
    b = counters(dev)
    trap("5,2000")  # boots rolled back: outside the time window
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AuthFailureCount"] >= 1 and x, 10, what="AuthFailure+1")
    check(d["AcceptedCount"] == 0, f"rollback accepted: {d}")
    return "LastError=" + read(dev, "LastError")["LastError"]


@case("v3:inform-replay-in-window")
def v3_inform_replay_window():
    """A captured Inform replayed within the 150 s window: ACKed, counted as duplicate, never re-accepted."""
    dev = v3_device()
    pdu = inform_bytes(v3_inform_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128")))
    time.sleep(1)  # let the original send settle before the baseline
    b = counters(dev)
    acks = replay(pdu, times=2, sport=40004)
    time.sleep(1.5)
    d = delta(b, counters(dev))
    check(acks == 2 and d["AcceptedCount"] == 1 and d["DuplicateInformCount"] == 1, f"acks={acks} delta {d}")
    return f"delta {d}"


@case("v3:inform-replay-stale")
def v3_inform_replay_stale():
    """Replayed > 150 s later: rejected as notInTimeWindow (anti-replay), no cache was cleared."""
    dev = v3_device()
    pdu = inform_bytes(v3_inform_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128")))
    time.sleep(160)
    b = counters(dev)
    acks = replay(pdu, times=1, sport=40005)
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AuthFailureCount"] >= 1 and x, 10, what="AuthFailure+1")
    check(d["AcceptedCount"] == 0 and d["DuplicateInformCount"] == 0, f"delta {d}")
    check(acks <= 1, "unexpected responses")  # a notInTimeWindow Report may answer it
    out = send(v3_inform_cmd(v3_args("AUTH_PRIV", "SHA256", "AES128")), check=False)
    check(out.returncode == 0, "a fresh Inform after the stale replay was not ACKed")
    return "LastError=" + read(dev, "LastError")["LastError"]


@case("restart:gateway-v3-inform")
def restart_gateway_inform():
    from st import engine_file, restart_gateway
    dev = v3_device()
    args = v3_args("AUTH_PRIV", "SHA256", "AES128")
    stale = inform_bytes(v3_inform_cmd(args))
    before = engine_file()
    local = read(dev, "LocalEngineId")["LocalEngineId"]
    restart_gateway()
    wait_status(dev, "Listening", 90)
    after = engine_file()
    check(after["engineId"] == before["engineId"], f"engineId changed {before['engineId']} -> {after['engineId']}")
    check(int(after["engineBoots"]) == int(before["engineBoots"]) + 1, f"boots {before['engineBoots']} -> "
          f"{after['engineBoots']}")
    check(read(dev, "LocalEngineId")["LocalEngineId"] == local, "LocalEngineId changed")
    b = counters(dev)
    replay(stale, times=1, sport=40006)  # a sender still holding the old boots
    d = wait_for(lambda: (x := delta(b, counters(dev)))["AuthFailureCount"] >= 1 and x, 10, what="AuthFailure+1")
    check(d["AcceptedCount"] == 0, f"old-boots Inform accepted: {d}")
    b = counters(dev)
    out = send(v3_inform_cmd(args), check=False)  # rediscovery picks up the new boots
    check(out.returncode == 0, f"v3 inform after restart not ACKed: {out.stderr.strip()[-200:]}")
    accepted_one(dev, b, pduType="INFORM")
    return f"engineBoots {before['engineBoots']} -> {after['engineBoots']}, engineId kept"


@case("module:reinstall")
def module_reinstall():
    from st import igdev, ROOT, wait_for as wf, opc, pin_gateway_identity
    dev = v2c_device()
    # igdev 0.3.1 can leave empty root-owned directories named *.modl behind; only files are archives
    modl = sorted(p for p in (ROOT.parent / ".igdev" / "modules").glob("*.modl") if p.is_file())[-1]
    igdev("module", "install", str(modl))
    igdev("gateway", "ensure")
    pin_gateway_identity()  # module install re-renders the compose file without the pinned MAC
    wf(lambda: opc("browse", "", "0") is not None, 120, 3, "OPC UA after reinstall")
    wait_status(dev, "Listening", 90)
    b = counters(dev)
    send(V2C_TRAP.format(c=COMM))
    accepted_one(dev, b)


@case("port:occupied-degraded-recovers")
def port_occupied():
    from st import run, gateway_info, IMAGE, poll, start_agent
    start_agent()
    holder = "st-port-holder"
    run(["docker", "rm", "-f", holder], check=False)
    run(["docker", "run", "-d", "--name", holder, "--network", f"container:{gateway_info()['container']}", IMAGE,
         "python", "-c", f"import socket,time;s=socket.socket(2,2);s.bind(('0.0.0.0',{TRAP_PORT}));time.sleep(3600)"])
    try:
        time.sleep(1)
        dev = v2c_device_no_wait()
        wait_status(dev, "Degraded", 30)
        err = read(dev, "LastError")["LastError"]
        value = poll(dev, "1.3.6.1.2.1.1.5.0", 5)
        check(value == "st-agent", f"polling broken while Trap is Degraded: {value!r}")
    finally:
        run(["docker", "rm", "-f", holder], check=False)
    t0 = time.time()
    wait_status(dev, "Listening", 40)
    took = time.time() - t0
    check(took <= 30, f"recovered after {took:.0f} s (> 30 s)")
    b = counters(dev)
    send(V2C_TRAP.format(c=COMM))
    accepted_one(dev, b)
    return f"recovered in {took:.0f} s; LastError while Degraded: {err}"


def v2c_device_no_wait(name="st-v2c"):
    create_device(name, host=me(), version="V2C", readCommunity=COMM, trapEnabled=True, trapPort=TRAP_PORT)
    return name


@case("poll:unaffected-by-traps")
def poll_with_traps():
    from st import poll, start_agent
    start_agent()
    dev = v2c_device()
    send(V2C_TRAP.format(c=COMM))
    check(poll(dev, "1.3.6.1.2.1.1.5.0", 5) == "st-agent", "polling failed on a Trap-enabled device")


@case("docker:udp162-mapping-source-ip")
def docker_udp162():
    """Q31: publish host UDP 162 -> container 10162 with a temporary override and record the source IP seen."""
    from st import COMPOSE, opc, pin_gateway_identity, recreate_gateway, run, IMAGE, wait_for as wf
    original = COMPOSE.read_text()
    check(original.count("    ports:\n") == 1 and "162:10162/udp" not in original, "unexpected compose layout")
    patched = original.replace("    ports:\n", "    ports:\n      - \"162:10162/udp\"  # M1d temporary\n", 1)
    try:
        recreate_gateway(patched)
        wf(lambda: opc("browse", "", "0") is not None, 180, 3, "OPC UA after recreate")
        dev = "st-any"
        create_device(dev, host="10.255.255.1", version="V2C", readCommunity=COMM, trapEnabled=True,
                      trapPort=TRAP_PORT, trapSourceAllowlist="0.0.0.0/0")
        wait_status(dev, "Listening", 60)
        seen = {}
        # 1) a sender on another Docker network, addressed to the host's default-bridge IP
        bridge_gw = run(["docker", "network", "inspect", "bridge", "-f",
                         "{{(index .IPAM.Config 0).Gateway}}"]).stdout.strip()
        b = counters(dev)
        p = run(["docker", "run", "--rm", IMAGE, "sh", "-c",
                 f"hostname -i; snmptrap -v 2c -c {COMM} {bridge_gw}:162 '' 1.3.6.1.6.3.1.1.5.3"], check=False)
        sender_ip = p.stdout.split()[0] if p.stdout.split() else "?"
        accepted_one(dev, b)
        seen["external-container"] = (sender_ip, json.loads(read(dev, "Last/Json")["Last/Json"])["source"])
        # 2) the host itself, to the published port on 127.0.0.1
        pdu = inform_bytes(V2C_INFORM.format(c=COMM))  # any v2c PDU; it is ACKed by the Gateway too
        b = counters(dev)
        import socket
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(3)
        s.sendto(bytes.fromhex(pdu), ("127.0.0.1", 162))
        try:
            s.recv(65535)
            acked = True
        except socket.timeout:
            acked = False
        s.close()
        accepted_one(dev, b)
        seen["host-localhost"] = ("127.0.0.1", json.loads(read(dev, "Last/Json")["Last/Json"])["source"],
                                  "inform ACK reached host" if acked else "no ACK at host")
        return json.dumps(seen)
    finally:
        delete_all_devices()
        recreate_gateway(original)
        pin_gateway_identity()
        wf(lambda: opc("browse", "", "0") is not None, 180, 3, "OPC UA after revert")


@case("cleanup")
def cleanup():
    delete_all_devices()


# ---------------------------------------------------------------- SET (#32)

SET_OID = "1.3.6.1.4.1.53864.1.0"
SET_ITEM = "RW.Integer32/" + SET_OID


@case("set:v2c-integer32-write-readback")
def v2c_set_integer32():
    return set_write_readback(trap=False)


@case("set:v2c-integer32-write-readback-trap-on")
def v2c_set_integer32_trap_on():
    out = set_write_readback(trap=True)
    b = counters("st-set")
    send(V2C_TRAP.format(c=COMM))
    accepted_one("st-set", b)
    return out + "; Trap still accepted"


def set_write_readback(trap):
    """OPC UA write -> SNMP SET with the Write Community -> agent value -> poll reads it back; READ_ONLY is refused."""
    from st import opc, poll, start_agent
    start_agent()
    sx(f"snmpset -v2c -c st-private localhost {SET_OID} i 5")
    dev = "st-set"
    create_device(dev, host=me(), version="V2C", readCommunity=COMM, writeCommunity="st-private",
                  accessMode="READ_WRITE", trapEnabled=trap, trapPort=TRAP_PORT)
    if trap:
        wait_status(dev, "Listening")
    check(poll(dev, SET_ITEM, 4) == 5, "initial poll did not read 5")
    nid = f"ns=1;s=[{dev}]{SET_ITEM}"
    status = json.loads([ln for ln in opc("write", nid, "Int32", "42").splitlines() if ln.startswith("{")][-1])[nid]
    check(status == "Good", f"write status {status}")
    agent = sx(f"snmpget -v2c -Oqv -c {COMM} localhost {SET_OID}").stdout.strip()
    check(agent == "42", f"agent value after SET: {agent!r}")
    back = poll(dev, SET_ITEM, 4)
    check(back == 42, f"poll read back {back!r}")
    update_device(dev, accessMode="READ_ONLY")
    time.sleep(2)
    ro = json.loads([ln for ln in opc("write", nid, "Int32", "7").splitlines() if ln.startswith("{")][-1])[nid]
    check(ro in ("BadNotWritable", "BadUserAccessDenied"), f"READ_ONLY write status {ro}")
    agent = sx(f"snmpget -v2c -Oqv -c {COMM} localhost {SET_OID}").stdout.strip()
    check(agent == "42", f"READ_ONLY write reached the agent: {agent!r}")
    return f"write Good, agent 5->42, poll {back}; READ_ONLY -> {ro}"
