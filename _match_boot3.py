import os, glob, struct, hashlib, sys

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um

BOOT_TARGET = "1087678f4926673ea93d5561f207bd5ac59afc5e315aceed26dd28be4c912550"
PAYLOAD_HASH = "7efb1875a81c5f24d38e86242a6c0299f4a9f22f9b4c49a096e0b54d15074746"
LOG_SIZE = 15608689
LOG_META = 954

def parse(path):
    with open(path, "rb") as f:
        if f.read(4) != b"CrAU":
            return None
        ver = struct.unpack(">Q", f.read(8))[0]
        msize = struct.unpack(">Q", f.read(8))[0]
        msig = struct.unpack(">I", f.read(4))[0]   # <-- the 4 bytes I missed
        meta = f.read(msize)
    m = um.DeltaArchiveManifest()
    m.ParseFromString(meta)
    return ver, msize, msig, m, len(meta)

cands = sorted(set(os.path.abspath(x) for x in glob.glob(r"workspace_injection/**/payload.bin", recursive=True)))
cands += [os.path.abspath("payload.bin")]
cands += sorted(os.path.abspath(x) for x in glob.glob(r"**/payload.bin", recursive=True) if "jadx" not in x)

seen = set()
for p in cands:
    if p in seen or not os.path.exists(p):
        continue
    seen.add(p)
    try:
        r = parse(p)
    except Exception as e:
        print(f"{p}  ERR {e}"); continue
    if r is None:
        print(f"{p}  NOT CrAU"); continue
    ver, msize, msig, m, _ = r
    ops = {}
    for pp in m.partitions:
        for op in pp.operations:
            if op.HasField("data_sha256_hash") and op.type in (0,1,2,3):  # REPLACE, REPLACE_BZ, MOVE?, REPLACE_XZ...
                ops.setdefault(pp.partition_name, []).append((op.type, op.data_length, op.data_sha256_hash.hex()))
    boot = None
    for name, lst in ops.items():
        if name == "boot":
            boot = lst[0][2]
    size = os.path.getsize(p)
    tags = []
    if size == LOG_SIZE: tags.append("SIZE==LOG")
    if msize == LOG_META: tags.append("META==LOG")
    if boot == BOOT_TARGET: tags.append("*** BOOT SHA MATCHES LOG ***")
    print(f"{p}\n   size={size} manifest={msize} metasig={msig} parts={[x.partition_name for x in m.partitions]} {' '.join(tags)}\n   boot_sha={boot}\n")
