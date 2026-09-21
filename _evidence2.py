import os, glob, re, struct, hashlib, base64

# 1) exact InstallPlan / applyPayload lines from Sept 6 log, untruncated
print("########## 1) RAW InstallPlan / payload lines (Sept 6) ##########")
for f in glob.glob("_logs/m1/**/*", recursive=True):
    if not os.path.isfile(f): continue
    try: txt=open(f,encoding="utf-8",errors="replace").read()
    except: continue
    for line in txt.splitlines():
        if re.search(r"InstallPlan|applyPayload|Marking new slot|payload:|metadata signature|doIviUpdate", line):
            print("  ", line.strip())

# 2) find any file with size 15608689 & list sizes of all payload/zip candidates
print("\n########## 2) size search (LOG payload size = 15608689) ##########")
TARGET=15608689
roots=["."]
for r in roots:
    for f in glob.glob(os.path.join(r,"**","*"), recursive=True):
        if not os.path.isfile(f): continue
        if "jadx" in f or ".venv" in f or "node_modules" in f: continue
        try: s=os.path.getsize(f)
        except: continue
        if s in (TARGET, 15610229, 15609209, 15573412, 15542136, 15609641, 16774747, 16774883):
            print(f"   {s:>12}  {f}")

# 3) factory 625 package
print("\n########## 3) factory packages ##########")
for z in glob.glob("**/update.zip", recursive=True) + glob.glob("**/*.zip", recursive=True):
    if "jadx" in z: continue
    zl=z.lower()
    if not ("new folder" in zl or "os/" in zl or "os\\" in zl): continue
    try:
        import zipfile
        zf=zipfile.ZipFile(z)
        pl=[n for n in zf.namelist() if n.endswith("payload.bin")]
        print(f"   {z}  zip={os.path.getsize(z)} payloads={pl}")
        for n in pl:
            d=zf.read(n)
            if d[:4]==b"CrAU":
                mlen=struct.unpack(">Q",d[12:20])[0]; sig=struct.unpack(">I",d[20:24])[0]
                print(f"       size={len(d)} manifest_len={mlen} metadata_sig={sig}")
    except Exception as e:
        print("   ERR",z,e)

# 4) Sept 7 properties
print("\n########## 4) Sept 7 sessions ##########")
for d in sorted(glob.glob("latest_car_log/APLog_*")):
    p=os.path.join(d,"properties")
    print("  SESSION", os.path.basename(d), "props?", os.path.exists(p))
    if os.path.exists(p):
        for line in open(p,encoding="utf-8",errors="replace"):
            if re.search(r"ro\.boot\.slot|incremental|versionname|build\.type|debuggable|ro\.secure|svc\.adbd", line):
                print("     ", line.strip())

# 5) origin of base64 blobs in logs
print("\n########## 5) base64-ish / UpdateVerifier lines ##########")
pat=re.compile(r"[A-Za-z0-9+/]{32,}={0,2}")
for f in glob.glob("_logs/m1/**/*", recursive=True)+glob.glob("latest_car_log/**/*", recursive=True):
    if not os.path.isfile(f): continue
    try: txt=open(f,encoding="utf-8",errors="replace").read()
    except: continue
    for line in txt.splitlines():
        if "UpdateVerifier" in line or "verifyPackage" in line or "doIviUpdate" in line or "GetBoot" in line or "boot" in line and len(pat.findall(line))>0:
            if "UpgradeService" in line or "update_engine" in line:
                print("  ", line.strip()[:300])
