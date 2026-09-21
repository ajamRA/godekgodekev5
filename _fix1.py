import struct, gzip, lzma, bz2, zipfile, sys, re

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation

def part_image(zp, want="boot"):
    d = zipfile.ZipFile(zp).read("payload.bin")
    mlen = struct.unpack(">Q", d[12:20])[0]; msig = struct.unpack(">I", d[20:24])[0]
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
    base = 24+mlen+msig
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
        f = lambda o, n: int(b[i+o:i+o+n], 16)
        mode, ns, fs = f(14,8), f(94,8), f(54,8)
        nm = b[i+110:i+110+ns-1].decode("utf-8","replace")
        do = i+110+ns; do += (4-do%4)%4
        out[nm] = (mode, b[do:do+fs])
        if nm == "TRAILER!!!": break
        i = do+fs; i += (4-i%4)%4
    return out

zip_path = sys.argv[1] if len(sys.argv) > 1 else "workspace_injection/output/update.zip"
rd = cpio(ramdisk(part_image(zip_path)))

print("### KANDUNGAN ATAS RAMDISK:"); 
tops = {}
for n in rd: tops[n.split("/")[0]] = tops.get(n.split("/")[0],0)+1
for k in sorted(tops): print(f"   {k:40s} {tops[k]}")

print("\n### ADA 'system/' dalam ramdisk?", any(n.startswith("system/") for n in rd))
print("### ADA 'system/bin/adbd'?", "system/bin/adbd" in rd)
print("### ADA 'system/bin/init'?", "system/bin/init" in rd)

print("\n### FAIL BERKAIT ADB/INIT:")
for n in sorted(rd):
    if re.search(r"adb|init|prop", n, re.I) and rd[n][0] & 0xF000 in (0x8000,0xA000):
        m,d = rd[n]
        print(f"   {'L' if m&0xF000==0xA000 else 'F'} {len(d):8d}  {n}" + (f"  -> {d.decode(errors='replace')}" if m&0xF000==0xA000 else ""))

ir = rd.get("init.rc", (0,b""))[1].decode("utf-8","replace")
print(f"\n### init.rc: {len(ir)} bytes, {ir.count(chr(10))} baris")
for marker in ["on boot", "class_start hal", "service adbd", "start adbd", "prop.default", "on early-init", "on init", "on late-init", "on post-fs-data", "blank_screen", "service.adb.tcp.port"]:
    hit = [i+1 for i,l in enumerate(ir.splitlines()) if marker in l]
    print(f"   '{marker:24s}' -> lines {hit[:8] if hit else 'TIADA'}")

print("\n### init.rc PENUH:")
for i,l in enumerate(ir.splitlines(),1):
    print(f"   {i:4d}| {l}")
