import struct, gzip, lzma, bz2, zipfile, sys, difflib

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation

def payload_from_zip(zp):
    return zipfile.ZipFile(zp).read("payload.bin")

def manifest(d):
    mlen = struct.unpack(">Q", d[12:20])[0]; msig = struct.unpack(">I", d[20:24])[0]
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
    return m, 24+mlen+msig

def part_image(d, want="boot"):
    m, base = manifest(d)
    out = bytearray()
    for p in m.partitions:
        if p.partition_name != want: continue
        for op in p.operations:
            off = base + (op.data_offset if op.HasField("data_offset") else 0)
            if op.type == IO.REPLACE: out += d[off:off+op.data_length]
            elif op.type == IO.REPLACE_XZ: out += lzma.decompress(d[off:off+op.data_length])
            elif op.type == IO.REPLACE_BZ: out += bz2.decompress(d[off:off+op.data_length])
            elif op.type == IO.ZERO: out += b"\x00"*op.data_length
    return bytes(out), m

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

def typ(mode):
    return {0x8000:"f",0xA000:"l",0x4000:"d"}.get(mode & 0xF000, "?")

cases = {
 "ORIG ": ("img", "workspace_injection/original_backup/boot.img"),
 "SEP6 ": ("zip", "D:/Mama/Reconstructed/Archives/zip/9 files_000009.zip"),
 "SEP7 ": ("zip", "workspace_injection/output/update.unsigned.zip"),
 "FINAL": ("zip", "workspace_injection/output/update.zip"),
}

rd_ = {}
print("="*100); print("PARTITION DALAM SETIAP PAYLOAD"); print("="*100)
for name,(k,path) in cases.items():
    if k == "img":
        rd_[name] = cpio(ramdisk(open(path,"rb").read()))
        print(f"{name}: boot.img terus   files_in_ramdisk={len(rd_[name])}")
    else:
        d = payload_from_zip(path)
        img, m = part_image(d, "boot")
        rd_[name] = cpio(ramdisk(img))
        print(f"{name}: partitions={[p.partition_name for p in m.partitions]}  count={len(m.partitions)}  files_in_ramdisk={len(rd_[name])}")

print()
print("="*100); print("SENARAI RAMDISK ORIG (ada 'first_stage_ramdisk'? ada root init.rc?)"); print("="*100)
for n in sorted(rd_["ORIG"]):
    md, dt = rd_["ORIG"][n]
    print(f"  {typ(md)} {len(dt):8d}  {n}")

def dump(name, fname, maxlines=200):
    a = rd_["ORIG"].get(fname, (0,b""))[1]
    b = rd_.get(name, {}).get(fname, (0,b""))[1]
    if a == b:
        print(f"\n### {fname}: ORIG == {name}"); return
    print(f"\n### DIFF {fname}:  ORIG({len(a)}B) -> {name}({len(b)}B)")
    try:
        ta = a.decode("utf-8","replace").splitlines()
        tb = b.decode("utf-8","replace").splitlines()
        dl = list(difflib.unified_diff(ta, tb, "ORIG", name, lineterm="", n=1))
        for l in dl[:maxlines]: print("   ", l)
        if len(dl) > maxlines: print(f"    ... ({len(dl)-maxlines} baris lagi)")
    except Exception as e:
        print("   (binari)", e)

TEXT = ["init.rc", "prop.default", "default.prop", "debug_ramdisk/adb_debug.prop",
        "w", "p", "first_stage_ramdisk/init.rc", "init.environ.rc", "fstab.emc21b",
        "fstab.mt6771", "fstab.enableswap", "system/etc/init/hw/init.rc"]

for name in ["SEP7 ", "FINAL"]:
    print("\n" + "="*100); print(f"PERBEZAAN RAMDISK  ORIG -> {name}"); print("="*100)
    for fn in TEXT:
        if fn in rd_["ORIG"] or fn in rd_[name]:
            dump(name, fn)

print("\n" + "="*100); print("SEP7 -> FINAL (apa yang final tambah yang sept7 TIADA)"); print("="*100)
for fn in TEXT:
    if fn in rd_["SEP7 "] or fn in rd_["FINAL"]:
        a = rd_["SEP7 "].get(fn,(0,b""))[1]; b = rd_["FINAL"].get(fn,(0,b""))[1]
        if a != b:
            print(f"\n### {fn}: SEP7({len(a)}) -> FINAL({len(b)})")
            dl = list(difflib.unified_diff(a.decode("utf-8","replace").splitlines(),
                                           b.decode("utf-8","replace").splitlines(),
                                           "SEP7", "FINAL", lineterm="", n=1))
            for l in dl[:200]: print("   ", l)
