import struct, lzma, gzip, os, hashlib, re

def split_boot(d):
    assert d[:8] == b"ANDROID!", d[:8]
    v = struct.unpack("<9I", d[8:44])
    ksize, rsize, ssize, page = v[0], v[2], v[4], v[7]
    n = 1
    k_off = n*page; n += (ksize + page - 1)//page
    r_off = n*page; n += (rsize + page - 1)//page
    s_off = n*page
    return dict(k=ksize, r=rsize, s=ssize, page=page), d[k_off:k_off+ksize], d[r_off:r_off+rsize], d[s_off:s_off+ssize]

def decomp(b):
    for name, fn in [("gzip", gzip.decompress), ("xz", lzma.decompress)]:
        try: return name, fn(b)
        except Exception: pass
    return None, b

def cpi_list(cpio):
    out = {}; i = 0
    while i + 110 <= len(cpio):
        if cpio[i:i+6] not in (b"070701", b"070702"): break
        f = lambda o, n: int(cpio[i+o:i+o+n], 16)
        namesize = f(94, 8); filesize = f(54, 8)
        name = cpio[i+110:i+110+namesize-1].decode("utf-8", "replace")
        doff = i + 110 + namesize
        doff += (4 - (doff % 4)) % 4
        out[name] = cpio[doff:doff+filesize]
        if name == "TRAILER!!!": break
        i = doff + filesize
        i += (4 - (i % 4)) % 4
    return out

def load(path):
    d = open(path, "rb").read()
    h, k, r, s = split_boot(d)
    cn, raw = decomp(r)
    return dict(sha=hashlib.sha256(d).hexdigest(), hdr=h, comp=cn, files=cpi_list(raw))

O = load("workspace_injection/original_backup/boot.img")
P = load("workspace_injection/patched_build/boot.img")

print(f"ORIG sha={O['sha']}  comp={O['comp']}  files={len(O['files'])}")
print(f"PATCH sha={P['sha']}  comp={P['comp']}  files={len(P['files'])}")
print(f"hdr orig={O['hdr']}\nhdr patc={P['hdr']}\n")

print("== FILES DIFFERING ==")
allnames = sorted(set(O['files']) | set(P['files']))
for n in allnames:
    a = O['files'].get(n); b = P['files'].get(n)
    if a is None:
        print(f"  + ADDED   {n}  ({len(b)})"); continue
    if b is None:
        print(f"  - REMOVED {n}  ({len(a)})"); continue
    if a != b:
        # show whether it's a real content change
        print(f"  ~ CHANGED {n}  {len(a)} -> {len(b)}")
        if len(a) < 4000 and len(b) < 4000:
            try:
                ta = a.decode(); tb = b.decode()
                import difflib
                for l in list(difflib.unified_diff(ta.splitlines(), tb.splitlines(), "orig/"+n, "patch/"+n, lineterm=""))[:60]:
                    print("       " + l)
            except Exception:
                print("       (binary)")
