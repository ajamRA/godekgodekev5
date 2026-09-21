import os, struct, gzip, lzma, zipfile, sys, hashlib

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation

def boot_from_payload(d):
    mlen = struct.unpack(">Q", d[12:20])[0]; msig = struct.unpack(">I", d[20:24])[0]
    base = 24+mlen+msig
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
    out = bytearray()
    for p in m.partitions:
        if p.partition_name != "boot": continue
        for op in p.operations:
            t, dl = op.type, op.data_length
            off = base + (op.data_offset if op.HasField("data_offset") else 0)
            if t == IO.REPLACE: out += d[off:off+dl]
            elif t == IO.REPLACE_XZ: out += lzma.decompress(d[off:off+dl])
            elif t == IO.REPLACE_BZ: out += __import__("bz2").decompress(d[off:off+dl])
            elif t == IO.ZERO: out += b"\x00"*dl
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
        ns, fs = f(94,8), f(54,8)
        nm = b[i+110:i+110+ns-1].decode("utf-8","replace")
        do = i+110+ns; do += (4-do%4)%4
        out[nm] = b[do:do+fs]
        if nm == "TRAILER!!!": break
        i = do+fs; i += (4-i%4)%4
    return out

zf = zipfile.ZipFile("workspace_injection/output/update.unsigned.zip")
SEPT7 = cpio(ramdisk(boot_from_payload(zf.read("payload.bin"))))
ORIG  = cpio(ramdisk(open("workspace_injection/original_backup/boot.img","rb").read()))
PATCH = cpio(ramdisk(open("workspace_injection/patched_build/boot.img","rb").read()))

names = ["system/bin/init", "init", "init.rc", "prop.default", "default.prop",
         "w", "p", "debug_ramdisk/adb_debug.prop"]
print(f"{'file':34s} {'ORIG':>10} {'PATCHED':>10} {'SEPT7-FLASHED':>14}  match")
for n in names:
    a, b, c = ORIG.get(n), PATCH.get(n), SEPT7.get(n)
    ha = hashlib.sha256(a).hexdigest()[:8] if a is not None else "-"
    hb = hashlib.sha256(b).hexdigest()[:8] if b is not None else "-"
    hc = hashlib.sha256(c).hexdigest()[:8] if c is not None else "-"
    m = "SEPT7==PATCHED" if (c is not None and b is not None and c == b) else ("SEPT7==ORIG!!!" if (c is not None and a is not None and c == a) else "?")
    print(f"{n:34s} {ha:>10} {hb:>10} {hc:>14}  {m}   sizes {len(a) if a else 0}/{len(b) if b else 0}/{len(c) if c else 0}")

# hook byte check
o, p_, s = ORIG.get("system/bin/init", b""), PATCH.get("system/bin/init", b""), SEPT7.get("system/bin/init", b"")
if len(o) == len(p_) == len(s):
    diffs = [i for i in range(len(o)) if o[i] != p_[i]]
    diffs_s = [i for i in range(len(o)) if o[i] != s[i]]
    print(f"\ninit binary: len={len(o)}")
    print(f"  ORIG vs PATCHED  : {len(diffs)} bytes differ at {[hex(x) for x in diffs[:20]]}")
    print(f"  ORIG vs SEPT7    : {len(diffs_s)} bytes differ at {[hex(x) for x in diffs_s[:20]]}")
    print(f"  0xA9F50 area ORIG={o[0xA9F50:0xA9F60].hex()} PATCH={p_[0xA9F50:0xA9F60].hex()} SEPT7={s[0xA9F50:0xA9F60].hex()}")
