import os, glob, re

def scan(label, path, pats, ctx=0, limit=200):
    print("="*100); print(label, "->", path); print("="*100)
    if not os.path.isdir(path):
        print("  (tiada dir)"); return
    rx = re.compile("|".join(pats))
    hits = 0
    for root, _, fns in os.walk(path):
        for fn in fns:
            fp = os.path.join(root, fn)
            try:
                lines = open(fp, "r", encoding="utf-8", errors="replace").read().splitlines()
            except Exception:
                continue
            for i, line in enumerate(lines):
                if rx.search(line):
                    lo = max(0, i-ctx); hi = min(len(lines), i+ctx+1)
                    for j in range(lo, hi):
                        print(f"  [{fn}:{j+1}] {lines[j][:220]}")
                    hits += 1
                    if hits >= limit:
                        print("  ...(limit)"); return
    print(f"  total={hits}")

g6 = "_logs/m1/APLog_2026_0906_154855__24"
g7 = sorted(glob.glob("latest_car_log/APLog_2026_0907*"))
g7 = g7[0] if g7 else ""

# fokus: adb / prop.default / avb / debuggable / usb config / init.rc
PAT = [r"prop\.default", r"default\.prop", r"adb", r"debuggable", r"libfs_avb",
       r"HASHTREE", r"ro\.secure", r"usb\.config", r"init\.rc", r"avb", r"AVB"]
for lbl, p in [("SEPT6", g6), ("SEPT7", g7)]:
    scan(lbl, p, PAT, ctx=0, limit=150)
