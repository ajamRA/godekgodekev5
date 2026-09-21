import os, glob, re, base64

LOGDIRS = sorted(glob.glob("_logs/m1/APLog_*")) + sorted(glob.glob("latest_car_log/APLog_*"))
if os.path.isdir("_logs/m2"):
    LOGDIRS += sorted(glob.glob("_logs/m2/APLog_*"))

KEYS = ["ro.boot.slot_suffix","ro.build.version.incremental","ro.debuggable","ro.secure","init.svc.adbd","ro.build.type"]
PAT = re.compile(r"judgeUpdate|doIviUpdate|applyPayload|onPayloadApplicationComplete|InstallPlan: new_update|"
                 r"McuUpdateCallback onUpgradeComplete|socPackageVerifier|MSG_TO_SOC_VERIFIER|"
                 r"persist\.update_engine\.updateing|updateValue:|Reboot|rebooting|reboot")

print("=" * 120)
for d in LOGDIRS:
    print(f"\n########## SESSION {d}")
    p = os.path.join(d, "properties")
    if os.path.exists(p):
        kv = {}
        for line in open(p, encoding="utf-8", errors="replace"):
            m = re.match(r"\[([^\]]+)\]:\s*\[(.*)\]", line.strip())
            if m: kv[m.group(1)] = m.group(2)
        print("   PROPS: " + "  ".join(f"{k}={kv.get(k,'?')}" for k in KEYS))
    else:
        print("   (no properties)")

    for f in glob.glob(os.path.join(d, "**", "*"), recursive=True):
        if not os.path.isfile(f): continue
        try: txt = open(f, encoding="utf-8", errors="replace").read()
        except Exception: continue
        for line in txt.splitlines():
            if not PAT.search(line): continue
            if re.search(r"sdv-flow|file transfer payload", line): continue
            m = re.match(r"\s*(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", line)
            ts = m.group(1) if m else "??"
            body = line.strip()
            body = re.sub(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+", "", body)
            print(f"   [{ts}] {body[:240]}")
