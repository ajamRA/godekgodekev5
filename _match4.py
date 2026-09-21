import os, glob, struct, hashlib, zipfile, sys

def h(b): return hashlib.sha256(b).hexdigest()

LOG_HASH = "7efb1875a81c5f24d38e86242a6c0299f4a9f22f9b4c49a096e0b54d15074746"
LOG_SIZE = 15608689
LOG_META = 954

def hdr(d):
    assert d[:4] == b"CrAU"
    ver = struct.unpack(">Q", d[4:12])[0]
    mlen = struct.unpack(">Q", d[12:20])[0]
    sig = struct.unpack(">I", d[20:24])[0]
    return ver, mlen, sig

def report(tag, d):
    n = len(d)
    ver, mlen, sig = hdr(d)
    blob0 = 24 + mlen + sig
    blobs = d[blob0:n-264] if n > 264 else b""
    cands = {
        "whole file": d,
        "minus last 264 (payload hash per update_engine)": d[:n-264],
        "header+manifest+metasig+blobs": d[:n-264],
        "hdr+man+blobs (skip metasig)": d[:24+mlen] + d[blob0:n-264],
        "blobs only": blobs,
    }
    print(f"--- {tag}  size={n} ver={ver} manifest_len={mlen} metadata_sig={sig} blob0={blob0}")
    for k, v in cands.items():
        hh = h(v)
        print(f"      {k:52s} {hh}" + ("   <<<<< MATCHES LOG" if hh == LOG_HASH else ""))

print("### payload.bin files")
for p in sorted(set(glob.glob("**/payload.bin", recursive=True))):
    if "jadx" in p: continue
    try: report(p, open(p, "rb").read())
    except Exception as e: print(f"--- {p}  ERR {e}")
    print()

print("### payload.bin inside update.zip files")
for z in sorted(set(glob.glob("**/*.zip", recursive=True))):
    if "jadx" in z or "mobilelog" in z: continue
    try:
        zf = zipfile.ZipFile(z)
    except Exception:
        continue
    names = [n for n in zf.namelist() if n.endswith("payload.bin")]
    if not names:
        continue
    for n in names:
        try:
            d = zf.read(n)
            report(f"{z}::{n}", d)
        except Exception as e:
            print(f"--- {z}::{n} ERR {e}")
    print()

print(f"### LOG reference: size={LOG_SIZE} metadata_size={LOG_META} hash={LOG_HASH}")
