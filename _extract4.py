import os, glob, struct, hashlib, zipfile, sys, lzma, bz2

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation
print("enum:", {n: getattr(IO, n) for n in
      ["REPLACE","REPLACE_BZ","MOVE","BSDIFF","SOURCE_COPY","SOURCE_BSDIFF","REPLACE_XZ","ZERO","DISCARD","BROTLI_BSDIFF","PUFFDIFF","ZUCCHINI"]})

def extract(d, part="boot"):
    if d[:4] != b"CrAU": return None, "not payload"
    mlen = struct.unpack(">Q", d[12:20])[0]
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
    for p in m.partitions:
        if p.partition_name != part: continue
        out = bytearray()
        for op in p.operations:
            t = op.type
            dl = op.data_length
            off = op.data_offset if op.HasField("data_offset") else None
            try:
                if t == IO.REPLACE:   out += d[off:off+dl]
                elif t == IO.REPLACE_BZ: out += bz2.decompress(d[off:off+dl])
                elif t == IO.REPLACE_XZ: out += lzma.decompress(d[off:off+dl])
                elif t == IO.ZERO:   out += b"\x00"*dl
                elif t == IO.DISCARD: pass
                else: return None, f"op {t}"
            except Exception as e:
                return None, f"op {t} decode err"
        return bytes(out), None
    return None, "no boot partition"

MARK = [b"ro.debuggable=1", b"ro.secure=0", b"service.adb.tcp.port=5555", b"persist.adb.tcp.port=5555",
        b"userdebug", b"adb_debug.prop", b"/first_stage_ramdisk/p", b"blank_screen",
        b"ro.adb.secure=0", b"prop.default"]

LOGSHA = {"1087678f4926673ea93d5561f207bd5ac59afc5e315aceed26dd28be4c912550": "SEPT-6 APPLIED",
          "3654956915782bfc95f098969f3acbcc0840427b8ce57c8e850ff2fc75823e77": "SEPT-7 APPLIED",
          "08f9e86065b39a8d3d67a8b475ce51d12d2a9a55ecce3cb5288ab17c212c876a": "our OUTPUT payload",
          "43e1f3d80b12f4972cedc9d6bcadc8e8b79ddf448b3740d65a84787d17d64091": "our candidate_hook"}
BOOTIMG = {"380a915f9e5bd4c4d1147d1be7ebb211d6afa41891748b4e373aeb848c4f7081": "ORIGINAL factory boot.img"}

def report(label, blob):
    boot, err = extract(blob)
    if err:
        print(f"  {label}: {err}"); return
    h = hashlib.sha256(boot).hexdigest()
    hits = {x.decode(): boot.count(x) for x in MARK if boot.count(x)}
    tag = LOGSHA.get(h) or BOOTIMG.get(h) or ""
    print(f"  {label}")
    print(f"     size={len(blob)} boot={len(boot)} sha256={h[:20]}…  {tag}")
    print(f"     ADB markers: {hits if hits else 'NONE'}")

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
