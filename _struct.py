import struct, gzip, lzma, bz2, zipfile, sys, os

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation

def part_image(zp, want="boot"):
    d = zipfile.ZipFile(zp).read("payload.bin")
    mlen = struct.unpack(">Q", d[12:20])[0]; msig = struct.unpack(">I", d[20:24])[0]
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen]); base = 24+mlen+msig
    out = bytearray()
    for p in m.partitions:
        if p.partition_name != want: continue
        for op in p.operations:
            off = base + (op.data_offset if op.HasField("data_offset") else 0)
            if op.type == IO.REPLACE: out += d[off:off+op.data_length]
            elif op.type == IO.REPLACE_XZ: out += lzma.decompress(d[off:off+op.data_length])
            elif op.type == IO.REPLACE_BZ: out += bz2.decompress(d[off:off+op.data_length])
            elif op.type == IO.ZERO: out += b"\x00"*op.data_length
    return bytes(out)

def ramdisk(boot):
    v = struct.unpack("<9I", boot[8:44]); k, r, page = v[0], v[2], v[7]
    n = 1 + (k+page-1)//page
    rd = boot[n*page:n*page+r]
    for fn in (gzip.decompress, lzma.decompress):
        try: return fn(rd)
        except Exception: pass
    return rd

def cpio(b):
    out = {}; i = 0
    while i+110 <= len(b):
        if b[i:i+6] not in (b"070701", b"070702"): break
        f = lambda o,n: int(b[i+o:i+o+n],16)
        mode, ns, fs = f(14,8), f(94,8), f(54,8)
        nm = b[i+110:i+110+ns-1].decode("utf-8","replace")
        do = i+110+ns; do += (4-do%4)%4
        out[nm] = (mode, b[do:do+fs])
        if nm == "TRAILER!!!": break
        i = do+fs; i += (4-i%4)%4
    return out

# gunakan ORIGINAL factory boot sebagai asas (paling selamat)
ORIG = open("workspace_injection/original_backup/boot.img","rb").read()
rd = cpio(ramdisk(ORIG))

def kind(m): return {0x8000:"F",0xA000:"L",0x4000:"D"}.get(m&0xF000,"?")

print("### first_stage_ramdisk/* :")
for n in sorted(rd):
    if n.startswith("first_stage_ramdisk"):
        m,d = rd[n]
        extra = f"  -> {d.decode(errors='replace')}" if kind(m)=="L" else ""
        print(f"   {kind(m)} {len(d):8d}  {n}{extra}")

print("\n### system/etc/init/ dalam ramdisk (mini-recovery):")
s = [n for n in rd if n.startswith("system/etc/init/")]
print("   count:", len(s), "->", s[:12])

print("\n### system/etc/init/hw/init.rc dalam ramdisk?", "system/etc/init/hw/init.rc" in rd)
if "system/etc/init/hw/init.rc" in rd:
    d = rd["system/etc/init/hw/init.rc"][1]
    print("   lines:", d.count(b"\n"), "size:", len(d))

print("\n### ramdisk prop.default (baris ro.* penting):")
pd = rd.get("prop.default",(0,b""))[1].decode("latin1","replace")
import re
for l in pd.splitlines():
    if re.match(r"\s*(ro\.(secure|debuggable|adb\.secure|build\.type)|service\.adb|persist\.adb)", l):
        print("   ", l)
print("   total lines:", pd.count("\n"))

print("\n### adakah 'extract/' ada dump system?")
for base, dirs, files in os.walk("extract"):
    for f in files[:20]:
        print("   ", os.path.join(base,f))

print("\n### cari init.rc 800-900 baris di mana-mana (dump system):")
import glob
cands = []
for pat in ["extract/**/*init.rc*", "**/system/etc/init/hw/init.rc", "parts/**/*.img"]:
    cands += glob.glob(pat, recursive=True)
for c in sorted(set(cands))[:30]:
    print("   cand:", c, os.path.getsize(c) if os.path.isfile(c) else "")
