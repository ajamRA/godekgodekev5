import struct, gzip, lzma, bz2, zipfile, sys, hashlib

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation

def payload_bytes_from_zip(zp):
    z = zipfile.ZipFile(zp); return z.read("payload.bin")

def boot_from_payload(d):
    mlen = struct.unpack(">Q", d[12:20])[0]; msig = struct.unpack(">I", d[20:24])[0]
    base = 24 + mlen + msig
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
    parts = [p.partition_name for p in m.partitions]
    out = bytearray()
    for p in m.partitions:
        if p.partition_name != "boot": continue
        for op in p.operations:
            t, dl = op.type, op.data_length
            off = base + (op.data_offset if op.HasField("data_offset") else 0)
            if t == IO.REPLACE: out += d[off:off+dl]
            elif t == IO.REPLACE_XZ: out += lzma.decompress(d[off:off+dl])
            elif t == IO.REPLACE_BZ: out += bz2.decompress(d[off:off+dl])
            elif t == IO.ZERO: out += b"\x00"*dl
    return bytes(out), parts, len(m.partitions)

def ramdisk(boot):
    v = struct.unpack("<9I", boot[8:44]); k, r, page = v[0], v[2], v[7]
    n = 1 + (k+page-1)//page
    rd = boot[n*page:n*page+r]
    for fn in (gzip.decompress, lzma.decompress):
        try: return fn(rd)
        except Exception: pass
    return rd

def cpio_full(b):
    out = {}
    i = 0
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

def kind(mode):
    t = mode & 0xF000
    return {0x8000:"file",0xA000:"symlink",0x4000:"dir"}.get(t, hex(mode))

print("="*100)
print("PARTISYEN DALAM PAYLOAD (apa yang pakej kita tulis sebenarnya)")
print("="*100)
for name, fn in [("ORIG factory", None),
                 ("Sept6 flashed", "D:/Mama/Reconstructed/Archives/zip/9 files_000009.zip"),
                 ("Sept7 flashed", "output/update.unsigned.zip"),
                 ("FINAL (E:)", "output/update.zip")]:
    if fn is None:
        print(f"{name:16s}: (boot.img terus, bukan payload)"); continue
    d = payload_bytes_from_zip(fn)
    _, parts, n = boot_from_payload(d)
    print(f"{name:16s}: partitions={parts}  count={n}")

print()
print("="*100)
print("STRUKTUR RAMDISK ORIG (boot kilang)")
print("="*100)
orig_rd = cpio_full(ramdisk(open("workspace_injection/original_backup/boot.img","rb").read()))
sept7_boot, _, _ = boot_from_payload(payload_bytes_from_zip("output/update.unsigned.zip"))
s7_rd = cpio_full(ramdisk(sept7_boot))
final_boot, _, _ = boot_from_payload(payload_bytes_from_zip("output/update.zip"))
fin_rd = cpio_full(ramdisk(final_boot))

KEY = ["init", "init.rc", "prop.default", "default.prop", "fstab.*", "system/bin/init"]
print("--- direktori TERATAS ramdisk ORIG ---")
top = sorted({n.split("/")[0] for n in orig_rd})
print(top)
print()
print("--- carian ciri SAR ---")
for probe in ["first_stage_ramdisk", "system/etc/init/hw/init.rc", "system/etc/prop.default",
              "system/etc/init/blank_screen.rc", "debug_ramdisk/adb_debug.prop"]:
    hits = [n for n in orig_rd if n.startswith(probe)]
    print(f"  ORIG  {probe:36s} -> {hits if hits else 'TIADA'}")
print()
print("--- 'init' dan 'init.rc' ORIG: symlink atau fail? ---")
for k in ["init", "init.rc", "default.prop", "prop.default"]:
    if k in orig_rd:
        mode, data = orig_rd[k]
        extra = f" -> target='{data.decode(errors='replace')}'" if kind(mode)=="symlink" else ""
        print(f"  {k:16s} {kind(mode):8s} size={len(data):7d}{extra}")
print()
print("--- 'init' dalam FINAL ---")
mode, data = fin_rd["init"]
print(f"  init {kind(mode)} size={len(data)} -> {data[:40]}")
print()
print("--- SENARAI PENUH ramdisk ORIG (name | kind | size) ---")
for n in sorted(orig_rd):
    mode, data = orig_rd[n]
    print(f"  {n:52s} {kind(mode):8s} {len(data):8d}")
