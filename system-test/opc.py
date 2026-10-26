"""Minimal OPC UA client for the system tests.

Usage (inside the systest container):
  python opc.py browse [nodeid] [depth]
  python opc.py read <nodeid> [<nodeid> ...]     -> one JSON line {nodeid: value}
  python opc.py write <nodeid> <variant-type> <value> -> one JSON line {nodeid: status}

Connects with Basic256Sha256/SignAndEncrypt as the Gateway admin user
(IG_USER / IG_PASS env). The client certificate lives in /work/.pki and must
be trusted once on the Gateway (see lib.sh: opc_trust).
"""
import asyncio, json, os, sys
from pathlib import Path
from asyncua import Client, ua
from asyncua.crypto.cert_gen import setup_self_signed_certificate
from asyncua.crypto.security_policies import SecurityPolicyBasic256Sha256
from cryptography.x509.oid import ExtendedKeyUsageOID

URL = os.environ.get("OPC_URL", "opc.tcp://gateway:62541")
PKI = Path("/work/.pki")
URI = "urn:snmp-driver:systest"


async def connect():
    PKI.mkdir(exist_ok=True)
    cert, key = PKI / "cert.der", PKI / "key.pem"
    await setup_self_signed_certificate(key, cert, URI, "snmp-systest",
                                        [ExtendedKeyUsageOID.CLIENT_AUTH], {"commonName": "snmp-systest"})
    c = Client(URL, timeout=10)
    c.application_uri = URI
    c.set_user(os.environ["IG_USER"])
    c.set_password(os.environ["IG_PASS"])
    await c.set_security(SecurityPolicyBasic256Sha256, str(cert), str(key),
                         mode=ua.MessageSecurityMode.SignAndEncrypt)
    await c.connect()
    return c


def plain(v):
    if isinstance(v, (bytes, bytearray)):
        return v.hex()
    if isinstance(v, (str, int, float, bool)) or v is None:
        return v
    return str(v)


async def walk(node, depth, maxd):
    for ch in await node.get_children():
        try:
            bn = (await ch.read_browse_name()).Name
            nc = await ch.read_node_class()
        except Exception as e:
            print("  " * depth + f"? [{ch.nodeid.to_string()}] {type(e).__name__}")
            continue
        val = ""
        if nc == ua.NodeClass.Variable:
            try:
                val = " = " + repr(plain(await ch.read_value()))[:100]
            except Exception as e:
                val = " ! " + type(e).__name__
        print("  " * depth + f"{bn} [{ch.nodeid.to_string()}]{val}")
        if depth < maxd and nc == ua.NodeClass.Object:
            await walk(ch, depth + 1, maxd)


async def main(argv):
    c = await connect()
    try:
        if argv[0] == "browse":
            start = c.get_node(argv[1]) if len(argv) > 1 and argv[1] else c.nodes.objects
            await walk(start, 0, int(argv[2]) if len(argv) > 2 else 2)
        elif argv[0] == "poll":
            # poll <seconds> <nodeid> ...: subscribe (which starts SNMP polling) and report the last value
            seconds, nids = float(argv[1]), argv[2:]
            last = {nid: "NO_DATA" for nid in nids}

            class Handler:
                def datachange_notification(self, node, val, data):
                    dv = data.monitored_item.Value
                    nid = node.nodeid.to_string()
                    last[nid] = plain(val) if dv.StatusCode.is_good() else "BAD:" + dv.StatusCode.name

            sub = await c.create_subscription(1000, Handler())
            await sub.subscribe_data_change([c.get_node(n) for n in nids])
            await asyncio.sleep(seconds)
            await sub.delete()
            print(json.dumps(last))
        elif argv[0] == "read":
            out = {}
            for nid in argv[1:]:
                dv = await c.get_node(nid).read_data_value(raise_on_bad_status=False)
                out[nid] = plain(dv.Value.Value) if dv.StatusCode.is_good() else "BAD:" + dv.StatusCode.name
            print(json.dumps(out))
        elif argv[0] == "write":
            # write <nodeid> <VariantType name, e.g. Int32> <value>: one Value write, report the status
            nid, vt = argv[1], getattr(ua.VariantType, argv[2])
            num = vt.name.startswith(("Int", "UInt", "Byte", "SByte"))
            val = int(argv[3]) if num else float(argv[3]) if vt.name in ("Float", "Double") else argv[3]
            res = await c.uaclient.write_attributes([ua.NodeId.from_string(nid)],
                                                    [ua.DataValue(ua.Variant(val, vt))], ua.AttributeIds.Value)
            print(json.dumps({nid: res[0].name}))
    finally:
        await c.disconnect()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))
