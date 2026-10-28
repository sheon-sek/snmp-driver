# Test-only sink. Records are raw JSON strings supplied by the driver.
from java.io import File, FileOutputStream
from java.lang import Thread
from java.util.concurrent.locks import ReentrantLock

RECORDS = "/tmp/snmp-driver-st-records.jsonl"
CONTROL = "/tmp/snmp-driver-st-records-control.json"
VERSION = "v1"


def onNotification(record):
    config = {}
    if File(CONTROL).exists():
        config = system.util.jsonDecode(system.file.readFileAsString(CONTROL))
    mode = config.get("mode", "normal")
    if mode == "exception":
        raise ValueError("ST_RECORD_HANDLER_ERROR")
    if mode == "slow":
        Thread.sleep(long(config.get("delayMillis", 6000)))
    version = config.get("version", VERSION)
    # Gateway globals keep the same lock when project scripts are reloaded.
    lock = system.util.getGlobals().setdefault("snmp-driver.st-records.lock", ReentrantLock())
    lock.lock()
    try:
        entry = system.util.jsonEncode({"version": version, "record": record}) + "\n"
        stream = FileOutputStream(RECORDS, True)
        try:
            stream.write(entry.encode("UTF-8"))
        finally:
            stream.close()
    finally:
        lock.unlock()
    return version


def onNotificationNoop(record):
    # Storm baseline (#51): measures the driver's per-Record cost without the sink's file I/O.
    return VERSION
