import os, re, struct, lzma, zlib, sys, glob, zipfile

print("#" * 90)
print("### A) Extract boot from payloads -> search ADB injection markers")
print("#" * 90)
MARKS = [b"ro.debuggable=1", b"service.adb.tcp.port", b"persist.adb.tcp.port", b"start adbd",
         b"ro.adb.secure=0", b"adb_enabled", b"ro.secure=0", b"userdebug", b"prop.default",
         b"blank_screen", b"/first_stage_ramdisk"]

def extract_boot(payload_bytes):
    d = payload_bytes
    if d[:4] != b"CrAU":
        return None
    mlen = struct.unpack(">Q", d[12:20])[0]
    meta = d[24:24+mlen]
    try:
        sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
        from payload_dumper import update_metadata_pb2 as um
    except Exception as e:
        return None
    m = um.DeltaArchiveManifest()
    m.ParseFromString(meta)
    for p in m.partitions:
        if p.partition_name != "boot":
            continue
        out = bytearray()
        for op in p.operations:
            # protobuf: op.type (1=REPLACE,2=REPLACE_BZ,3=REPLACE_XZ,6=ZERO? actually 6=ZERO, 7=DISCARD)
            t = op.type
            dl = op.data_length
            do = op.data_offset
            if t == 1:      # REPLACE
                out += d[do:do+dl]
            elif t == 2:    # REPLACE_BZ
                out += bz2.decompress(d[do:do+dl])
            elif t == 3:    # REPLACE_XZ
                out += lzma.decompress(d[do:do+dl])
            elif t == 6:    # ZERO
                out += b"\x00" * dl
        return bytes(out)
    return None

import bz2
for label, path in [("our output", "workspace_injection/output/payload.bin"),
                    ("our candidate_hook", "workspace_injection/candidate_hook/update_hook.zip"),
                    ("orig factory", "payload.bin")]:
    if not os.path.exists(path): 
        print("MISSING", path); continue
    data = open(path, "rb").read()
    if path.endswith(".zip"):
        zf = zipfile.ZipFile(path)
        data = zf.read("payload.bin")
    boot = extract_boot(data)
    if boot is None:
        print(f"{label}: could not extract boot"); continue
    print(f"\n{label}: boot extracted = {len(boot)} bytes")
    found = {m.decode(errors='replace'): boot.count(m) for m in MARKS if boot.count(m)}
    print("   markers:", found if found else "NONE")
    # find ramdisk
    if boot[:8] == b"ANDROID!":
        kv = struct.unpack("<9I", boot[8:44])
        ksize, rsize, ssize, page = kv[0], kv[2], kv[4], kv[7]
        off = ((ksize + page - 1)//page + 1) * page
        rd = boot[off:off+rsize]
        print(f"   kernel={ksize} ramdisk={rsize} page={page} ramdisk@ {off} magic={rd[:4]!r}")
        fm = {m.decode(errors='replace'): rd.count(m) for m in MARKS if rd.count(m)}
        print("   markers IN RAMDISK:", fm if fm else "NONE")

print()
print("#" * 90)
print("### B) UpgradeService: Handler / messages / systemUpgrade")
print("#" * 90)
S = "jadx_upgradeservice/sources/ecarx/upgradeservice/UpgradeService.java"
L = open(S, encoding="utf-8", errors="replace").read().splitlines()
def dump(a, b, tag=""):
    print(f"\n----- {S} [{a}-{b}] {tag} -----")
    for i in range(a-1, min(b, len(L))):
        print(f"{i+1:5d}| {L[i]}")
# find handler class + message constants
for i, l in enumerate(L, 1):
    if re.search(r"extends Handler|Handler\s*\(\)|public static final int MSG_|void handleMessage|static final int MSG|private static final int", l):
        print(f"{i:5d}| {l.strip()}")
