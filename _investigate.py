import os, glob, struct, hashlib, re, sys

def sha(b): return hashlib.sha256(b).hexdigest()

# ---------- 1) identify payload ranges ----------
LOG_HASH = "7efb1875a81c5f24d38e86242a6c0299f4a9f22f9b4c49a096e0b54d15074746"
print("=== payload.bin range hashes (output) ===")
for p in ["workspace_injection/output/payload.bin", "payload.bin"]:
    if not os.path.exists(p): continue
    d = open(p, "rb").read()
    n = len(d)
    hdr = 24
    mlen = struct.unpack(">Q", d[8:16])[0]
    msig = struct.unpack(">I", d[16:20])[0]
    blob_off = hdr + mlen + msig
    blob_end = n - 264  # payload signature is last 264
    tests = {
        "full file": d,
        "hdr+manifest only": d[:hdr+mlen],
        "hdr+manifest+metasig (metadata)": d[:blob_off],
        "manifest+metasig+blobs": d[hdr:blob_end],
        "hdr+manifest+blobs (skip metasig)": d[:hdr+mlen] + d[blob_off:blob_end],
        "blobs only": d[blob_off:blob_end],
        "full minus last 264": d[:n-264],
    }
    print(f"  {p}  size={n} manifest_len={mlen} metasig={msig} blob_off={blob_off} blob_len={blob_end-blob_off}")
    for k,v in tests.items():
        h = sha(v)
        mark = "  <<<<<<< MATCHES LOG HASH" if h == LOG_HASH else ""
        print(f"     {k:38s} {h}{mark}")
    print()

# ---------- 2) find all logs ----------
print("=== candidate logs on disk ===")
pats = ["**/mobilelog.zip", "**/APLog*", "**/*.log"]
seen=set()
for pat in pats:
    for f in glob.glob(pat, recursive=True):
        if "jadx" in f: continue
        if f in seen: continue
        seen.add(f)
for f in sorted(seen)[:60]:
    try: sz=os.path.getsize(f)
    except: sz=-1
    print(f"  {sz:>10}  {f}")
