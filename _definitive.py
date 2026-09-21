import os, glob, struct, hashlib, zipfile, sys

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um

LOG_BOOT = "1087678f4926673ea93d5561f207bd5ac59afc5e315aceed26dd28be4c912550"
LOG_MANIFEST = 954
LOG_PAYLOAD = 15608689

def inspect(path_or_bytes, label):
    if isinstance(path_or_bytes, str):
        d = open(path_or_bytes, "rb").read()
    else:
        d = path_or_bytes
    if d[:4] != b"CrAU":
        return None
    ver = struct.unpack(">Q", d[4:12])[0]
    msize = struct.unpack(">Q", d[12:20])[0]
    msig = struct.unpack(">I", d[20:24])[0]
    if ver < 2:
        msig = 0
    m = um.DeltaArchiveManifest()
    m.ParseFromString(d[24:24+msize])
    info = {}
    for p in m.partitions:
        h = ""
        if p.HasField("new_partition_info") and p.new_partition_info.HasField("hash"):
            h = p.new_partition_info.hash.hex()
        info[p.partition_name] = h
    return dict(label=label, size=len(d), ver=ver, manifest=msize, metasig=msig,
                parts=info, boot=info.get("boot", ""))

print(f"LOG: payload_size={LOG_PAYLOAD} manifest={LOG_MANIFEST} boot_partition_sha={LOG_BOOT}\n")

results = []
for p in sorted(set(glob.glob("**/payload.bin", recursive=True))):
    if "jadx" in p or ".venv" in p: continue
    try:
        r = inspect(p, p)
    except Exception as e:
        r = None
    if r: results.append(r)

for z in sorted(set(glob.glob("**/*.zip", recursive=True))):
    if "jadx" in z or "mobilelog" in z or ".venv" in z: continue
    try: zf = zipfile.ZipFile(z)
    except Exception: continue
    for n in zf.namelist():
        if n.endswith("payload.bin"):
            try: r = inspect(zf.read(n), f"{z}::{n}")
            except Exception: r = None
            if r: results.append(r)

for r in results:
    tags = []
    if r["boot"] == LOG_BOOT: tags.append("*** BOOT PARTITION SHA MATCHES LOG ***")
    if r["manifest"] == LOG_MANIFEST: tags.append("(manifest==954)")
    if r["size"] == LOG_PAYLOAD: tags.append("(size==log)")
    print(f"{r['label']}")
    print(f"    size={r['size']} manifest={r['manifest']} metasig={r['metasig']} boot_partition_sha={r['boot']} {' '.join(tags)}")
