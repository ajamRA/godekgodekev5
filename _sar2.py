import os, glob, re

DIRS = [
    ("SEPT6 boot slot A", "_logs/m1/APLog_2026_0906_154855__24"),
    ("SEPT7 boot slot A", None),
]
g7 = sorted(glob.glob("latest_car_log/APLog_2026_0907*"))
DIRS[1] = ("SEPT7 boot slot A", g7[0] if g7 else None)

PATTERNS = [
    "adb.tcp.port", "adbd", "debuggable", "blank_screen", "init.rc",
    "prop.default", "default.prop", "first_stage", "Parsing file",
    "ro.adb.secure", "sys.usb.config", "adb_enabled",
]
rx = re.compile("|".join(re.escape(p) for p in PATTERNS))

for label, d in DIRS:
    print("="*100)
    print(f"{label}: {d}")
    print("="*100)
    if not d or not os.path.isdir(d):
        print("  (tiada)"); continue
    files = []
    for root, _, fns in os.walk(d):
        for fn in fns:
            files.append(os.path.join(root, fn))
    print(f"  {len(files)} fail dalam sesi ini")
    hits = 0
    for path in files:
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                for i, line in enumerate(fh, 1):
                    if rx.search(line):
                        print(f"  [{os.path.basename(path)}:{i}] {line.rstrip()[:200]}")
                        hits += 1
                        if hits > 120:
                            print("  ... (dipotong)"); break
        except Exception:
            pass
        if hits > 120: break
    print(f"  total baris padan: {hits}")
    print()
