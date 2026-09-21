import os, sys, hashlib, glob, zipfile, tempfile, struct, subprocess

TARGET = "1087678f4926673ea93d5561f207bd5ac59afc5e315aceed26dd28be4c912550"

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection")
try:
    import payload_dumper
except Exception as e:
    print("import payload_dumper failed:", e)
    payload_dumper = None

def sha_file(p, limit=None):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b: break
            h.update(b)
    return h.hexdigest()

cands = []
for pat in ["workspace_injection/**/payload.bin", "*payload.bin"]:
    cands += glob.glob(pat, recursive=True)
cands = sorted(set(os.path.abspath(c) for c in cands))
print("calon payload:", len(cands))

# parse our payload's manifest to get boot.img raw sha256 directly
def payload_boot_sha(path):
    """Return raw boot.img sha256 from payload manifest, using update_metadata_pb2."""
    try:
        from payload_dumper import update_metadata_pb2 as um
    except Exception:
        import update_metadata_pb2 as um
    with open(path, "rb") as f:
        magic = f.read(4)
        if magic != b"CrAU":
            return ("BADMAGIC", None)
        version = struct.unpack(">Q", f.read(8))[0]
        manifest_len = struct.unpack(">Q", f.read(8))[0]
        meta = f.read(manifest_len)
    m = um.DeltaArchiveManifest()
    m.ParseFromString(meta)
    res = {}
    for p in m.partitions:
        for op in p.operations:
            if op.type == um.InstallOperation.REPLACE or op.type == um.InstallOperation.REPLACE_BZ or op.type == um.InstallOperation.REPLACE_XZ:
                res[p.partition_name] = op.data_sha256_hash.hex()
    return ("OK", res)

for p in cands:
    sz = os.path.getsize(p)
    try:
        st, res = payload_boot_sha(p)
    except Exception as e:
        st, res = ("ERR", str(e))
    boot = res.get("boot") if isinstance(res, dict) else None
    mark = ""
    if boot == TARGET:
        mark = "  <<<<<< MATCH InstallPlan new boot sha!"
    print(f"\n{p}\n   size={sz} status={st} boot_sha={boot}{mark}")
