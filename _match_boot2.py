import os, glob, struct, hashlib, sys

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um

TARGET = "1087678f4926673ea93d5561f207bd5ac59afc5e315aceed26dd28be4c912550"

def boot_sha(path):
    with open(path, "rb") as f:
        if f.read(4) != b"CrAU":
            return None
        struct.unpack(">Q", f.read(8))
        mlen = struct.unpack(">Q", f.read(8))[0]
        meta = f.read(mlen)
    m = um.DeltaArchiveManifest()
    m.ParseFromString(meta)
    out = {}
    for p in m.partitions:
        # average op size / combine not needed; only REPLACE ops carry data_sha256
        for op in p.operations:
            if op.HasField("data_sha256_hash") and op.type in (
                um.InstallOperation.REPLACE,
                um.InstallOperation.REPLACE_BZ,
                um.InstallOperation.REPLACE_XZ,
                um.InstallOperation.ZERO,
            ):
                out[p.partition_name] = op.data_sha256_hash.hex()
    return out, m

cands = sorted(set(os.path.abspath(x) for x in glob.glob(r"workspace_injection/**/payload.bin", recursive=True)))
cands += [os.path.abspath("payload.bin")]
for p in cands:
    try:
        res, m = boot_sha(p)
    except Exception as e:
        print(p, "ERR", e); continue
    boot = (res or {}).get("boot")
    flag = ""
    if boot == TARGET:
        flag = "  <<<<<<<<<< MATCH!"
    parts = ",".join(f"{pp.partition_name}({len(pp.operations)})" for pp in m.partitions)
    print(f"{p}\n   size={os.path.getsize(p)} parts=[{parts}]\n   boot_sha={boot}{flag}\n")
