import os, glob, struct, hashlib, zipfile, sys, lzma, gzip

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation

def extract(d, part="boot"):
    if d[:4] != b"CrAU": return None, "not payload"
    mlen = struct.unpack(">Q", d[12:20])[0]
    msig = struct.unpack(">I", d[20:24])[0]
    base = 24 + mlen + msig                 # <-- blob base (this was my bug)
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
    for p in m.partitions:
        if p.partition_name != part: continue
        out = bytearray()
        for op in p.operations:
            t, dl = op.type, op.data_length
            off = base + (op.data_offset if op.HasField("data_offset") else 0)
            if t == IO.REPLACE:      out += d[off:off+dl]
            elif t == IO.REPLACE_BZ: out += __import__("bz2").decompress(d[off:off+dl])
            elif t == IO.REPLACE_XZ: out += lzma.decompress(d[off:off+dl])
            elif t == IO.ZERO:       out += b"\x00"*dl
            elif t == IO.DISCARD:    pass
            else: return None, f"op {t}"
        return bytes(out), None
    return None, "no boot"

MARK = [b"ro.debuggable=1", b"ro.secure=0", b"service.adb.tcp.port=5555", b"persist.adb.tcp.port=5555",
        b"adb_debug.prop", b"ro.adb.secure=0", b"/first_stage_ramdisk/p", b"blank_screen",
        b"userdebug", b"prop.default"]

KNOWN = {"1087678f4926673ea93d5561f207bd5ac59afc5e315aceed26dd28be4c912550": "*** APPLIED SEPT 6 ***",
         "3654956915782bfc95f098969f3acbcc0840427b8ce57c8e850ff2fc75823e77": "*** APPLIED SEPT 7 ***",
         "08f9e86065b39a8d3d67a8b475ce51d12d2a9a55ecce3cb5288ab17c212c876a": "our FINAL output",
         "43e1f3d80b12f4972cedc9d6bcadc8e8b79ddf448b3740d65a84787d17d64091": "our candidate_hook",
         "380a915f9e5bd4c4d1147d1be7ebb211d6afa41891748b4e373aeb848c4f7081": "ORIGINAL factory boot"}

def report(label, blob):
    boot, err = extract(blob)
    if err: print(f"  {label}: {err}"); return
    h = hashlib.sha256(boot).hexdigest()
    hits = {x.decode(): boot.count(x) for x in MARK if boot.count(x)}
    tag = KNOWN.get(h, "")
    print(f"  {label}\n     size={len(blob)} boot={len(boot)} sha={h[:24]}…  {tag}\n     markers={hits if hits else 'NONE'}")

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
