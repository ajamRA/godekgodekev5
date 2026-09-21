import os, glob, struct, hashlib, zipfile, sys, lzma, bz2

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um

MARKS = [b"ro.debuggable=1", b"service.adb.tcp.port", b"persist.adb.tcp.port", b"start adbd",
         b"ro.adb.secure=0", b"adb_enabled", b"ro.secure=0", b"userdebug",
         b"prop.default", b"blank_screen", b"/first_stage_ramdisk", b"adb_debug"]

def extract_boot(d):
    if d[:4] != b"CrAU": return None, None
    mlen = struct.unpack(">Q", d[12:20])[0]
    m = um.DeltaArchiveManifest()
    m.ParseFromString(d[24:24+mlen])
    for p in m.partitions:
        if p.partition_name != "boot": continue
        out = bytearray()
        for op in p.operations:
            t, dl = op.type, op.data_length
            off = op.data_offset if op.HasField("data_offset") else 0
            # REPLACE=0, REPLACE_BZ=1, REPLACE_XZ=6, ZERO=7
            if t == 0:   out += d[off:off+dl]
            elif t == 1: out += bz2.decompress(d[off:off+dl])
            elif t == 6: out += lzma.decompress(d[off:off+dl])
            elif t == 7: out += b"\x00"*dl
            else:
                return None, f"unsupported op type {t}"
        declared = p.new_partition_info.hash.hex() if (p.HasField("new_partition_info") and p.new_partition_info.HasField("hash")) else None
        return bytes(out), declared
    return None, None

def report(label, blob):
    boot, declared = extract_boot(blob)
    if boot is None:
        print(f"  {label}: EXTRACT FAIL ({declared})"); return
    sha = hashlib.sha256(boot).hexdigest()
    marks = {m.decode(): boot.count(m) for m in MARKS if boot.count(m)}
    ok = "OK" if sha == declared else "SHA-MISMATCH!"
    print(f"  {label}\n     boot_len={len(boot)} sha256={sha} declared={declared} [{ok}]")
    print(f"     markers={marks if marks else 'NONE'}")

print("### payloads (from .bin and inside .zip)")
seen = set()
for p in sorted(set(glob.glob("**/payload.bin", recursive=True))):
    if "jadx" in p or ".venv" in p: continue
    report(p, open(p, "rb").read())
for z in sorted(set(glob.glob("**/*.zip", recursive=True))):
    if "jadx" in z or "mobilelog" in z or ".venv" in z: continue
    try: zf = zipfile.ZipFile(z)
    except Exception: continue
    for n in zf.namelist():
        if n.endswith("payload.bin"):
            report(f"{z}::{n}", zf.read(n))

print("\n### raw boot.img files")
for p in sorted(set(glob.glob("**/boot*.img", recursive=True))):
    if "jadx" in p: continue
    d = open(p, "rb").read()
    print(f"  {p} size={len(d)} magics={d[:8]!r} sha256={hashlib.sha256(d).hexdigest()}")
    marks = {m.decode(): d.count(m) for m in MARKS if d.count(m)}
    print(f"     markers={marks if marks else 'NONE'}")
