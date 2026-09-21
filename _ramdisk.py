import struct, lzma, gzip, io, os, hashlib, re
try:
    import lz4
except Exception:
    lz4 = None

def split_boot(d):
    assert d[:8] == b"ANDROID!", d[:8]
    v = struct.unpack("<9I", d[8:44])
    ksize, rsize, ssize, page = v[0], v[2], v[4], v[7]
    n = 1
    k_off = n*page; n += (ksize + page - 1)//page
    r_off = n*page; n += (rsize + page - 1)//page
    s_off = n*page
    return dict(k=ksize, r=rsize, s=ssize, page=page, hdrver=v[8]), d[k_off:k_off+ksize], d[r_off:r_off+rsize], d[s_off:s_off+ssize]

def decomp_any(b):
    for name, fn in [("gzip", lambda x: gzip.decompress(x)),
                     ("lzma", lambda x: lzma.decompress(x)),
                     ("xz", lambda x: lzma.decompress(x))]:
        try: return name, fn(b)
        except Exception: pass
    try:
        if lz4:
            return "lz4", lz4.frame.decompress(b)
    except Exception:
        pass
    return None, b

def cpi_list(cpio):
    """parse cpio newc; return {name: data}"""
    out = {}
    i = 0
    while i + 110 <= len(cpio):
        if cpio[i:i+6] not in (b"070701", b"070702"):
            break
        f = lambda o, n: int(cpio[i+o:i+o+n], 16)
        namesize = f(94, 8); filesize = f(54, 8)
        name = cpio[i+110:i+110+namesize-1].decode("utf-8", "replace")
        doff = i + 110 + namesize
        doff += (4 - (doff % 4)) % 4
        data = cpio[doff:doff+filesize]
        out[name] = data
        if name == "TRAILER!!!": break
        i = doff + filesize
        i += (4 - (i % 4)) % 4
    return out

for label, path in [("ORIGINAL", "workspace_injection/original_backup/boot.img"),
                    ("PATCHED ", "workspace_injection/patched_build/boot.img")]:
    d = open(path, "rb").read()
    hdr, k, r, s = split_boot(d)
    cname, rd = decomp_any(r)
    files = cpi_list(rd)
    print(f"\n===== {label} {path}")
    print(f"   sha256={hashlib.sha256(d).hexdigest()}")
    print(f"   hdr={hdr} ramdisk_comp={cname} ramdisk_raw={len(rd)} files={len(files)}")
    names = [n for n in files if not n.startswith(".")]
    print("   FILES:")
    for n in names:
        print(f"      {n:60s} {len(files[n])}")
    # search injection markers inside each file
    MARK = [b"ro.debuggable=1", b"ro.secure=0", b"ro.adb.secure=0", b"service.adb.tcp.port",
            b"persist.adb.tcp.port", b"start adbd", b"adb_enabled", b"prop.default",
            b"blank_screen", b"userdebug", b"/first_stage_ramdisk"]
    print("   MARKERS per file:")
    for n, blob in files.items():
        hits = {m.decode(): blob.count(m) for m in MARK if blob.count(m)}
        if hits:
            print(f"      {n}: {hits}")
