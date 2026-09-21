import struct, gzip, lzma, re, glob, os

def ram(path):
    d = open(path, "rb").read()
    v = struct.unpack("<9I", d[8:44]); ksize, rsize, page = v[0], v[2], v[7]
    n = 1 + (ksize + page - 1)//page
    rd = d[n*page:n*page+rsize]
    for fn in (gzip.decompress, lzma.decompress):
        try: return fn(rd)
        except Exception: pass
    return rd

def cpio(b):
    out = {}; i = 0
    while i + 110 <= len(b):
        if b[i:i+6] not in (b"070701", b"070702"): break
        f = lambda o, n: int(b[i+o:i+o+n], 16)
        ns, fs = f(94,8), f(54,8)
        name = b[i+110:i+110+ns-1].decode("utf-8","replace")
        doff = i+110+ns; doff += (4-doff%4)%4
        out[name] = b[doff:doff+fs]
        if name == "TRAILER!!!": break
        i = doff+fs; i += (4-i%4)%4
    return out

# SEPT7 (applied) ramdisk
import zipfile, sys
sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation
zf = zipfile.ZipFile("workspace_injection/output/update.unsigned.zip")
d = zf.read("payload.bin")
mlen = struct.unpack(">Q", d[12:20])[0]; msig = struct.unpack(">I", d[20:24])[0]
base = 24+mlen+msig
m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
boot = bytearray()
for p in m.partitions:
    if p.partition_name != "boot": continue
    for op in p.operations:
        t, dl = op.type, op.data_length
        off = base + (op.data_offset if op.HasField("data_offset") else 0)
        if t == IO.REPLACE: boot += d[off:off+dl]
        elif t == IO.REPLACE_XZ: boot += lzma.decompress(d[off:off+dl])
        elif t == IO.REPLACE_BZ: boot += __import__("bz2").decompress(d[off:off+dl])
        elif t == IO.ZERO: boot += b"\x00"*dl
v = struct.unpack("<9I", boot[8:44]); ksize, rsize, page = v[0], v[2], v[7]
n = 1 + (ksize+page-1)//page
SEPT7 = cpio(gzip.decompress(boot[n*page:n*page+rsize]))

print("### 'p' file (the file bound over /system/etc/prop.default) — SEPT7")
if "p" in SEPT7:
    print(SEPT7["p"].decode("utf-8","replace")[:2500])
print("\n### 'w' file (bound over /system/etc/init/blank_screen.rc) — SEPT7")
if "w" in SEPT7:
    print(SEPT7["w"].decode("utf-8","replace")[:1500])
print("\n### init binary: which prop files does it load? (strings)")
initbin = SEPT7.get("system/bin/init", b"")
for pat in [b"/prop.default", b"/default.prop", b"/system/etc/prop.default", b"/vendor/default.prop",
            b"first_stage_ramdisk", b"/system/etc/init/", b"load_properties", b"blank_screen"]:
    print(f"   {pat.decode():32s} {initbin.count(pat)}")
print("\n### init.rc prop-load / mount lines (SEPT7)")
for line in SEPT7.get("init.rc", b"").decode("utf-8","replace").splitlines():
    if re.search(r"prop\.default|default\.prop|mount|bind|import|first_stage", line):
        print("   ", line)
