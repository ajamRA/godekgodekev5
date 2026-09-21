import os, glob, re

DIRS = sorted(glob.glob("_logs/m1/APLog_*")) + sorted(glob.glob("latest_car_log/APLog_*"))
KEYS = ["ro.boot.slot_suffix", "ro.boot.slot", "ro.build.version.incremental",
        "ro.build.version.versionname", "ro.build.type", "ro.debuggable", "ro.secure",
        "ro.adb.secure", "init.svc.adbd", "sys.usb.config", "persist.sys.usb.config",
        "ro.boot.verifiedbootstate", "service.adb.tcp.port", "persist.adb.tcp.port",
        "ro.build.fingerprint", "ro.boot.flash.locked"]

for d in DIRS:
    p = os.path.join(d, "properties")
    print("=" * 100)
    print("SESSION:", d, "  (properties exists:", os.path.exists(p), ")")
    if not os.path.exists(p):
        # try find any properties file deeper
        cand = glob.glob(os.path.join(d, "**", "properties"), recursive=True)
        print("   deeper:", cand)
        continue
    txt = open(p, encoding="utf-8", errors="replace").read()
    for line in txt.splitlines():
        m = re.match(r"\[([^\]]+)\]:\s*\[(.*)\]", line.strip())
        if not m:
            continue
        k, v = m.group(1), m.group(2)
        if k in KEYS:
            print(f"   {k:34s} = {v}")
    # also print any adb-ish keys not in list
    for line in txt.splitlines():
        if re.search(r"adb|debug|slot|build\.type|secure", line, re.I):
            m = re.match(r"\[([^\]]+)\]:\s*\[(.*)\]", line.strip())
            if m and m.group(1) not in KEYS:
                print(f"   (extra) {m.group(1):27s} = {m.group(2)}")
