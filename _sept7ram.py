import os, struct, hashlib, zipfile, sys, gzip, lzma, difflib

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um
IO = um.InstallOperation

def extract_boot(d):
    mlen = struct.unpack(">Q", d[12:20])[0]
    msig = struct.unpack(">I", d[20:24])[0]
    base = 24 + mlen + msig
    m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24+mlen])
    for p in m.partitions:
        if p.partition_name != "boot": continue
        out = bytearray()
        for op in p.operations:
            t, dl = op.type, op.data_length
            off = base + (op.data_offset if op.HasField("data_offset") else 0)
            if t == IO.REPLACE:      out += d[off:off+dl]
            elif t == IO.REPLACE_BZ: out += __import__("bz2").decompress(d[off:off+dl])
            elif t == IO.REPLACE_XZ: out += lzma.decompress(d[off:off+dl])
            elif t == IO.ZERO:       out += b"\x00"*dl
        return bytes(out)

def ramdisk_of(boot):
    assert boot[:8] == b"ANDROID!"
    v = struct.unpack("<9I", boot[8:44])
    ksize, rsize, page = v[0], v[2], v[7]
    n = 1
    n += (ksize + page - 1)//page
    off = n*page
    rd = boot[off:off+rsize]
    for fn in (gzip.decompress, lzma.decompress):
        try: return fn(rd)
        except Exception: pass
    return rd

def cpio(cpio_b):
    out = {}; i = 0
    while i + 110 <= len(cpio_b):
        if cpio_b[i:i+6] not in (b"070701", b"070702"): break
        f = lambda o, n: int(cpio_b[i+o:i+o+n], 16)
        namesize, filesize = f(94,8), f(54,8)
        name = cpio_b[i+110:i+110+namesize-1].decode("utf-8","replace")
        doff = i + 110 + namesize; doff += (4 - doff % 4) % 4
        out[name] = cpio_b[doff:doff+filesize]
        if name == "TRAILER!!!": break
        i = doff + filesize; i += (4 - i % 4) % 4
    return out

def load_from_payload(blob):
    return cpio(ramdisk_of(extract_boot(blob)))

def load_from_img(path):
    return cpio(ramdisk_of(open(path, "rb").read()))

zf = zipfile.ZipFile("workspace_injection/output/update.unsigned.zip")
SEPT7 = load_from_payload(zf.read("payload.bin"))
FINAL = load_from_img("workspace_injection/patched_build/boot.img")

print("### SEPT-7-FLASHED ramdisk (update.unsigned.zip) vs our FINAL patched_build/boot.img")
print("files:", len(SEPT7), "vs", len(FINAL))
alln = sorted(set(SEPT7) | set(FINAL))
for n in alln:
    a, b = SEPT7.get(n), FINAL.get(n)
    if a == b: continue
    if a is None: print(f"  only-in-FINAL  {n} ({len(b)})"); continue
    if b is None: print(f"  only-in-SEPT7  {n} ({len(a)})"); continue
    print(f"  DIFF {n}  {len(a)} -> {len(b)}")
    if (n in ("default.prop","prop.default","init.rc") or n.endswith(".rc") or n.endswith(".prop")) and len(a)<40000 and len(b)<40000:
        ta = a.decode("utf-8","replace").splitlines(); tb = b.decode("utf-8","replace").splitlines()
        for l in list(difflib.unified_diff(ta, tb, "sept7/"+n, "final/"+n, lineterm=""))[:40]:
            print("     "+l)

print("\n### ADB strings in SEPT-7 default.prop / prop.default")
for n in ("default.prop","prop.default"):
    if n in SEPT7:
        txt = SEPT7[n].decode("utf-8","replace")
        for line in txt.splitlines():
            if any(k in line for k in ("ro.secure","ro.debuggable","adb","userdebug","tcp.port")):
                print(f"   {n}: {line}")
print("\n### SEPT-7 init.rc adb lines")
if "init.rc" in SEPT7:
    for line in SEPT7["init.rc"].decode("utf-8","replace").splitlines():
        if "adb" in line.lower() or "prop.default" in line or "blank_screen" in line or "first_stage_ramdisk" in line:
            print("   ", line)
