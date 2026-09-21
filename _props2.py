import os, glob, re
DIRS = sorted(glob.glob("_logs/m1/APLog_*")) + sorted(glob.glob("latest_car_log/APLog_*"))
KEYS = ["ro.boot.slot_suffix","ro.build.version.incremental","ro.build.version.versionname",
        "ro.build.type","ro.debuggable","ro.secure","init.svc.adbd","sys.usb.config",
        "ro.build.fingerprint"]
print(f"{'SESSION':<52} {'slot':<4} {'build':<6} {'type':<8} {'dbg':<4} {'sec':<4} {'adbd':<8} ver")
for d in DIRS:
    p=os.path.join(d,"properties")
    if not os.path.exists(p): 
        print(d,"NO PROPS"); continue
    txt=open(p,encoding="utf-8",errors="replace").read()
    kv={}
    for line in txt.splitlines():
        m=re.match(r"\[([^\]]+)\]:\s*\[(.*)\]",line.strip())
        if m: kv[m.group(1)]=m.group(2)
    print(f"{os.path.basename(d):<52} {kv.get('ro.boot.slot_suffix',''):<4} {kv.get('ro.build.version.incremental',''):<6} {kv.get('ro.build.type',''):<8} {kv.get('ro.debuggable',''):<4} {kv.get('ro.secure',''):<4} {kv.get('init.svc.adbd',''):<8} {kv.get('ro.build.version.versionname','')}")
