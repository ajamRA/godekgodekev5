import os, glob, re

PAT = re.compile(r"update_engine|InstallPlan|applyPayload|onPayloadApplicationComplete|"
                 r"delta_performer|payload_verifier|PartitionInfo|Opening /dev/block|"
                 r"filesystem_verifier|Marking new slot|payload_metadata|"
                 r"UpgradeService: (doIviUpdate|socPackageVerifier|judgeUpdate|verifyPackage|McuUpdateCallback onUpgradeComplete|updateValue|onCreate)|"
                 r"UpgradeApplication|updateing")

for f in sorted(glob.glob("latest_car_log/**/*", recursive=True)):
    if not os.path.isfile(f): continue
    try: txt = open(f, encoding="utf-8", errors="replace").read()
    except Exception: continue
    hits = [l.strip() for l in txt.splitlines() if PAT.search(l)]
    if not hits: continue
    print(f"\n##### {f}  ({len(hits)} hits)")
    # dedupe while keeping order
    seen=set()
    for l in hits:
        k = re.sub(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+", "", l)
        if k in seen: continue
        seen.add(k)
        print("   ", l[:290])
