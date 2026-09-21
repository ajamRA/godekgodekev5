import os, re, glob, sys

BASE = r"D:/apps/emas-ota/_logs/m1"
PATS = re.compile(
    r"UpgradeService|update_engine|UpdateEngine|applyPayload|socPackageVerifier|updateing|"
    r"checkMcuUpgradeFile|onPayloadApplicationComplete|isCanDownVer|reboot_recovery|"
    r"update_verifier|slot-unbootable|verifiedboot|avb|vbmeta|bootctl|IBootControl|"
    r"UpdateVerifier|verify basic pass|payload|Payload|McuUpdateCallback|GpsUpdateCallback|"
    r"setActiveBootSlot|markBootSuccessful|boot slot|bootcontrol", re.I)

rows = []
for root, dirs, files in os.walk(BASE):
    for fn in files:
        p = os.path.join(root, fn)
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                for ln, line in enumerate(f, 1):
                    if PATS.search(line):
                        if "ReapLogF" in line or "PropSet" in line:
                            # keep only updateing-relevant prop dumps
                            m = re.search(r"\[persist\.update_engine\.updateing\]=\[(\w+)\]", line)
                            if not m:
                                continue
                        rows.append((fn, ln, line.rstrip()))
        except Exception:
            pass

# group counts per file for a quick overview
from collections import Counter
c = Counter(r[0] for r in rows)
print("=== FILES WITH MATCHES ===")
for k, v in sorted(c.items(), key=lambda x: -x[1]):
    print(f"{v:6d}  {k}")
print(f"TOTAL MATCHES: {len(rows)}")

# save full
with open(r"D:/apps/emas-ota/_logs/m1_trace.txt", "w", encoding="utf-8") as o:
    for fn, ln, line in rows:
        o.write(f"{fn}:{ln}: {line}\n")
print("saved -> _logs/m1_trace.txt")
