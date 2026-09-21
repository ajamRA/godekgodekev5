import os, re, glob
from collections import defaultdict

ROOTS = ["latest_car_log"]
PAT = re.compile(r"UpgradeService|update_engine|bootctrl|applyPayload|onPayloadApplicationComplete|"
                 r"verifyPackage|UpdateVerifier|judgeUpdate|updateing|socPackageVerifier|"
                 r"McuUpdateCallback|error|Error|fail|Fail|FAILED|mirror|payload|InstallPlan", re.I)

store = defaultdict(list)
for root in ROOTS:
    for f in glob.glob(os.path.join(root, "**", "*"), recursive=True):
        if not os.path.isfile(f): continue
        try: txt = open(f, encoding="utf-8", errors="replace").read()
        except Exception: continue
        for line in txt.splitlines():
            if PAT.search(line):
                store[f].append(line.strip()[:230])

for f in sorted(store):
    uniq = sorted(set(store[f]))
    print(f"\n##### {f}  ({len(store[f])} hits / {len(uniq)} uniq)")
    for l in uniq[:45]:
        print("   ", l)
