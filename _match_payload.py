import os, glob, hashlib

TARGET = "7efb1875a81c5f24d38e86242a6c0299f4a9f22f9b4c49a096e0b54d15074746"
SZ = 15608689
META = 954

def sha(b):
    return hashlib.sha256(b).hexdigest()

cands = sorted(set(os.path.abspath(x) for x in glob.glob(r"workspace_injection/**/payload.bin", recursive=True)))
cands += [os.path.abspath("payload.bin")]

for p in cands:
    if not os.path.exists(p):
        continue
    d = open(p, "rb").read()
    n = len(d)
    # payload hash in update_engine = sha256 of the payload *data* (blobs) ... but commonly
    # it's sha256 over the full payload including header. Compute several ranges.
    tests = {
        "full": d,
        "hdr+manifest+metasig+blobs (n-264)": d[: n - 264],
        "from manifest_len onwards": None,
    }
    row = [f"{p}", f"  size={n}"]
    if n == SZ:
        row.append("  << SIZE MATCHES InstallPlan")
    for name, buf in list(tests.items()):
        if buf is None:
            continue
        row.append(f"  sha256[{name}]={sha(buf)}" + ("  <<<< MATCH" if sha(buf) == TARGET else ""))
    print("\n".join(row))
    print()
