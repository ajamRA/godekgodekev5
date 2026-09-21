import os, glob, re

SESSIONS = sorted(glob.glob("_logs/m1/APLog_*")) + sorted(glob.glob("latest_car_log/APLog_*"))

def props(d):
    p = os.path.join(d, "properties")
    kv = {}
    if os.path.exists(p):
        for line in open(p, encoding="utf-8", errors="replace"):
            m = re.match(r"\[([^\]]+)\]:\s*\[(.*)\]", line.strip())
            if m: kv[m.group(1)] = m.group(2)
    return kv

print("### DECISIVE TEST: does the booted init see ro.debuggable=0 (stock) or 1 (our patch)?")
print("### and does adbd start?\n")
for d in SESSIONS:
    kv = props(d)
    boot = os.path.basename(d)
    logs = []
    for f in glob.glob(os.path.join(d, "**", "*"), recursive=True):
        if os.path.isfile(f) and os.path.basename(f) != "properties":
            try: logs.append((f, open(f, encoding="utf-8", errors="replace").read()))
            except Exception: pass

    def find(pat, limit=4):
        out = []
        for f, t in logs:
            for line in t.splitlines():
                if re.search(pat, line):
                    out.append(line.strip()[:190])
                    if len(out) >= limit: return out
        return out

    print(f"--- {boot}")
    print(f"    slot={kv.get('ro.boot.slot_suffix','?')} build={kv.get('ro.build.version.incremental','?')} "
          f"type={kv.get('ro.build.type','?')} debuggable={kv.get('ro.debuggable','?')} "
          f"secure={kv.get('ro.secure','?')} adbd={kv.get('init.svc.adbd','?')}")
    for tag, pat in [("action(ro.debuggable=0)", r"processing action \(ro\.debuggable=0"),
                     ("action(ro.debuggable=1)", r"processing action \(ro\.debuggable=1"),
                     ("blank_screen parsed", r"Parsing file /system/etc/init/blank_screen\.rc"),
                     ("adb PropSet", r"PropSet .*(service\.adb\.tcp\.port|persist\.adb\.tcp\.port)"),
                     ("sys.usb.config=adb", r"PropSet \[sys\.usb\.config\]=[^\]]*adb"),
                     ("adbd running", r"init\.svc\.adbd\].*running|starting service 'adbd'"),
                     ("prop.default load", r"prop\.default"),
                     ("first_stage mount", r"first_stage_ramdisk")]:
        hits = find(pat)
        if hits:
            print(f"    [{tag}]")
            for h in hits: print("        ", h)
