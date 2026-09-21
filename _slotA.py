import os, glob, re

# The two SLOT-A boots that happened right after a successful applyPayload:
#   _logs/m1/APLog_2026_0906_154855__24   (after Sept-6 flash)
#   latest_car_log/APLog_2026_0907_151750__3  (after Sept-7 flash)
PAT = re.compile(r"prop\.default|default\.prop|ro\.debuggable|ro\.secure|adb|adb_debug|"
                 r"first_stage|Switching root|bind|MS_BIND|blank_screen|Parsing file /system/etc/init|"
                 r"verity|dm-verity|avb|Slot|slot_suffix", re.I)

for d in ["_logs/m1/APLog_2026_0906_154855__24", "latest_car_log/APLog_2026_0907_151750__3"]:
    print("=" * 110)
    print("SESSION:", d)
    if not os.path.isdir(d):
        print("  MISSING"); continue
    for f in sorted(glob.glob(os.path.join(d, "**", "*"), recursive=True)):
        if not os.path.isfile(f): continue
        if os.path.basename(f) in ("properties",): continue
        try: txt = open(f, encoding="utf-8", errors="replace").read()
        except Exception: continue
        hits = [l.strip() for l in txt.splitlines() if PAT.search(l)]
        if not hits: continue
        seen = set(); shown = 0
        print(f"\n  --- {os.path.relpath(f, d)}  ({len(hits)} hits)")
        for l in hits:
            k = re.sub(r"^[\d\-\s:.]*", "", l)[:120]
            if k in seen: continue
            seen.add(k); shown += 1
            if shown > 32: print("      …"); break
            print("     ", l[:250])
