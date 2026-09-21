import os, re, glob

ROOTS = ["_logs/m1", "latest_car_log", "logs_backup"]
PAT = re.compile(r"adbd|ro\.debuggable|ro\.secure|adb_enabled|service\.adb|tcp\.port|is_debuggable|" 
                 r"prop\.default|blank_screen|first_stage|adb_debug|sys\.usb\.config|" 
                 r"slot_suffix|verifiedbootstate|build\.type|ro\.build\.version|" 
                 r"Injecting|MS_BIND|recovery|rollback|unbootable", re.I)

rows = []
for root in ROOTS:
    for f in glob.glob(os.path.join(root, "**", "*"), recursive=True):
        if not os.path.isfile(f):
            continue
        try:
            txt = open(f, encoding="utf-8", errors="replace").read()
        except Exception:
            continue
        for line in txt.splitlines():
            if PAT.search(line):
                m = re.match(r"(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+)", line)
                key = m.group(0) if m else ""
                rows.append((f, key, line.strip()[:220]))

# group by file for stability
from collections import defaultdict
g = defaultdict(list)
for f, k, l in rows:
    g[f].append((k, l))

for f in sorted(g):
    uniq = sorted(set(l for _, l in g[f]))
    print(f"\n########## {f}  ({len(g[f])} lines, {len(uniq)} unique) ##########")
    for l in uniq[:60]:
        print("   ", l)
