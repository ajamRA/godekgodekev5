import os, glob, struct, hashlib, zipfile, sys, lzma, bz2

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um

# payload_dumper op enums
R, RBZ, MOVE, BSDIFF, SRCCPY, SRCBSDIFF, RXZ, ZERO, DISCARD = range(9)

def extract(d, part="boot"):
    if d[:4] != b"CrAU": return None
    mlen = struct.unpack(">Q", d[12:20])[0]
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
    for p in m.partitions:
        if p.partition_name != part: continue
        out = bytearray()
        for op in p.operations:
            t, dl = op.type, op.data_length
            off = op.data_offset if op.HasField("data_offset") else 0
            if t == R:   out += d[off:off+dl]
            elif t == RBZ: out += bz2.decompress(d[off:off+dl])
            elif t == RXZ: out += lzma.decompress(d[off:off+dl])
            elif t == ZERO: out += b"\x00"*dl
            elif t == DISCARD: pass
            else: return ("UNSUPPORTED_OP_%d" % t)
        return bytes(out)
    return None

MARK = [b"ro.debuggable=1", b"ro.secure=0", b"service.adb.tcp.port=5555", b"persist.adb.tcp.port=5555",
        b"userdebug", b"adb_debug.prop", b"prop.default", b"/first_stage_ramdisk/p",
        b"blank_screen.rc", b"ro.adb.secure=0"]

LOGSHA = {"1087678f4926673ea93d5561f207bd5ac59afc5e315aceed26dd28be4c912550": "Sept6 applied",
          "3654956915782bfc95f098969f3acbcc0840427b8ce57c8e850ff2fc75823e77": "Sept7 applied",
          "08f9e86065b39a8d3d67a8b475ce51d12d2a9a55ecce3cb5288ab17c212c876a": "our output payload",
          "43e1f3d80b12f4972cedc9d6bcadc8e8b79ddf448b3740d65a84787d17d64091": "our candidate_hook"}

def sha_of_boot_img(boot):
    # manifest hash is of the partition (padded to 33554432); boot.img is already padded
    return hashlib.sha256(boot).hexdigest()

def report(label, blob):
    if blob[:4] != b"CrAU":
        print(f"  {label}: not a payload"); return
    mlen = struct.unpack(">Q", blob[12:20])[0]
    m = um.DeltaArchiveManifest(); m.ParseFromString(blob[24:24+mlen])
    manifests = {}
    for p in m.partitions:
        h = p.new_partition_info.hash.hex() if (p.HasField("new_partition_info") and p.new_partition_info.HasField("hash")) else ""
        manifests[p.partition_name] = h
    boot = extract(blob)
    if isinstance(boot, str):
        print(f"  {label}: extract {boot}"); return
    h = sha_of_boot_img(boot) if boot else None
    marks = {x.decode(): boot.count(x) for x in MARK if boot and boot.count(x)}
    tag = ""
    for t, nm in LOGS["items"].items() if False else LOGSHA.items():
        if h == t: tag = f"  <<< LOG: {nm}"
    print(f"  {label}")
    print(f"     payload_size={len(blob)} manifest={mlen} manifest_boot_sha={manifests.get('boot','')[:16]}…")
    print(f"     boot_extracted={len(boot) if boot else 0} sha256={h}{tag}")
    print(f"     markers={marks if marks else 'NONE'}")

print("### every payload.bin we have")
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
