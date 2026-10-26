#!/usr/bin/env python3
"""#42 upgrade / restore / clone steps. Run from the repo root on the WSL host, after `st.py setup`.

    python3 system-test/upgrade.py install <path.modl>      # install a module build, keep the pinned MAC
    python3 system-test/upgrade.py create-v100               # 1.0.0-shaped devices (no TRAP fields)
    python3 system-test/upgrade.py snapshot <label>          # poll values, statuses, stored config -> out/
    python3 system-test/upgrade.py compare <labelA> <labelB>
    python3 system-test/upgrade.py backup <file.gwbk>
    python3 system-test/upgrade.py restore <file.gwbk> [--local]
    python3 system-test/upgrade.py engine                    # trap-engine.properties and backups

Each step prints JSON; snapshots are kept in system-test/out/snap-<label>.json.
"""
import json
import sys
import time

sys.path.insert(0, __import__("os").path.dirname(__file__))
import st  # noqa: E402

DEVICES = {
    # name: (1.0.0 settings without TRAP fields, items polled for the comparison)
    "up-v2c": ({"version": "V2C", "readCommunity": "st-public"},
               ["1.3.6.1.2.1.1.5.0", "1.3.6.1.2.1.1.6.0", "OctetString/1.3.6.1.2.1.1.5.0"]),
    "up-v3": ({"version": "V3", "securityName": "st-v3poll", "securityLevel": "AUTH_PRIV", "authProtocol": "SHA256",
               "authPassphrase": "st-auth-pass-123", "privacyProtocol": "AES128",
               "privacyPassphrase": "st-priv-pass-456"},
              ["1.3.6.1.2.1.1.5.0", "1.3.6.1.2.1.1.6.0"]),
}
V100_FIELDS = ["host", "port", "ipFamily", "version", "readCommunity", "writeCommunity", "securityName",
               "securityLevel", "authProtocol", "authPassphrase", "privacyProtocol", "privacyPassphrase",
               "contextName", "accessMode", "timeoutMillis", "retries", "maxOidsPerRequest", "maxRequestBytes",
               "maxInflightPerDevice"]


def wait_opc(timeout=180):
    st.wait_for(lambda: st.opc("browse", "", "0") is not None, timeout, 3, "OPC UA")


def install(modl):
    st.igdev("module", "install", modl)
    st.igdev("gateway", "ensure")
    st.pin_gateway_identity()
    wait_opc()
    _, mods = st.api("GET", "/data/api/v1/modules/healthy")
    mine = [m for m in json.dumps(mods) and _iter(mods) if m.get("id") == "com.jw.snmpdriver"]
    return {"installed": modl, "module": mine}


def _iter(body):
    if isinstance(body, list):
        return body
    for v in body.values():
        if isinstance(v, list):
            return v
    return []


def create_v100():
    host = st.container_ip(st.CONTAINER)
    for name, (over, _) in DEVICES.items():
        if name in st.device_names():
            st.delete_device(name)
        s = {k: st.DEFAULTS[k] for k in V100_FIELDS}
        s.update(host=host, **over)
        for k in st.SECRET_FIELDS & set(s):
            if isinstance(s[k], str):
                s[k] = st.secret(s[k])
        st.api("POST", st.DEV_RES, [{"name": name, "enabled": True,
                                     "config": {"profile": {"type": st.DEVICE_TYPE}, "settings": s}}])
    return {"created": list(DEVICES)}


def snapshot(label):
    out = {"label": label, "time": time.strftime("%Y-%m-%dT%H:%M:%S"), "devices": {}}
    for name, (_, items) in DEVICES.items():
        dev = {}
        res = st.get_device(name)
        settings = res["config"]["settings"]
        dev["storedSettings"] = {k: ("<secret>" if isinstance(v, dict) else v) for k, v in settings.items()}
        dev["enabled"] = res.get("enabled")
        dev["poll"] = {i: st.poll(name, i, 6) for i in items}
        try:
            dev["traps"] = st.read(name, "Status", "LastError", "LocalEngineId")
        except Exception as e:  # noqa: BLE001 - 1.0.0 has no Traps folder
            dev["traps"] = f"unavailable: {type(e).__name__}"
        dev["diagnostics"] = st.read(name, "Diagnostics/Status", "Diagnostics/LastError",
                                     "Diagnostics/CircuitBreakerState")
        out["devices"][name] = dev
    path = st.OUT / f"snap-{label}.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2))
    return out


def compare(a, b):
    sa = json.loads((st.OUT / f"snap-{a}.json").read_text())
    sb = json.loads((st.OUT / f"snap-{b}.json").read_text())
    diff = {}
    for name in DEVICES:
        da, db = sa["devices"][name], sb["devices"][name]
        d = {}
        if da["poll"] != db["poll"]:
            d["poll"] = {"before": da["poll"], "after": db["poll"]}
        for k in set(da["storedSettings"]) | set(db["storedSettings"]):
            if da["storedSettings"].get(k) != db["storedSettings"].get(k):
                d.setdefault("settings", {})[k] = [da["storedSettings"].get(k), db["storedSettings"].get(k)]
        if da["diagnostics"].get("Diagnostics/Status") != db["diagnostics"].get("Diagnostics/Status"):
            d["status"] = [da["diagnostics"].get("Diagnostics/Status"), db["diagnostics"].get("Diagnostics/Status")]
        diff[name] = d or "identical"
    return diff


def backup(path):
    st.igdev("gateway", "api", "GET", "/data/api/v1/backup", "--output", path)
    return {"backup": path}


def restore(path, local=False):
    q = "restoreLocal=true" if local else "restoreLocal=false"
    st.igdev("gateway", "api", "POST", f"/data/api/v1/backup?{q}",
             "--header", "Content-Type: application/octet-stream", "--data", "@" + path)
    time.sleep(20)  # the Gateway restarts after a restore
    st.igdev("gateway", "ensure")
    wait_opc(300)
    return {"restored": path, "restoreLocal": local}


def engine():
    gi = st.gateway_info()
    files = st.run(["docker", "exec", gi["container"], "sh", "-c",
                    "cd /usr/local/bin/ignition/data/config/local/com.jw.snmpdriver && ls -1"]).stdout.split()
    return {"current": st.engine_file(), "files": files}


if __name__ == "__main__":
    st.OUT.mkdir(exist_ok=True)
    cmd, args = sys.argv[1], sys.argv[2:]
    fn = {"install": lambda: install(args[0]), "create-v100": create_v100, "snapshot": lambda: snapshot(args[0]),
          "compare": lambda: compare(args[0], args[1]), "backup": lambda: backup(args[0]),
          "restore": lambda: restore(args[0], "--local" in args), "engine": engine}[cmd]
    print(json.dumps(fn(), ensure_ascii=False, indent=2))
