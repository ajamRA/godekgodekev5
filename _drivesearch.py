import os, glob, struct, hashlib, zipfile, base64, sys

print("=== decode InstallPlan boot shas ===")
for b64, tag in [("EIdnj0kmZz6pPVVh8ge9WsWa/F4xWs7tJt0ovkyRJVA=", "Sept6"),
                 ("NlSVaRV4K/yV8JiWnzrLzAhAQnuM5XyOhQ/y/HWCPnc=", "Sept7")]:
    print(f"  {tag}: {base64.b64decode(b64).hex()}")

sys.path.insert(0, r"D:/apps/emas-ota/workspace_injection/.venv/Lib/site-packages")
from payload_dumper import update_metadata_pb2 as um

def inspect_bytes(d):
    if len(d) < 24 or d[:4] != b"CrAU": return None
    ver = struct.unpack(">Q", d[4:12])[0]
    msize = struct.unpack(">Q", d[12:20])[0]
    msig = struct.unpack(">I", d[20:24])[0] if ver >= 2 else 0
    if 24 + msize > len(d): return None
    m = um.DeltaArchiveManifest()
    try: m.ParseFromString(d[24:24+msize])
    except Exception: return None
    out = {"ver": ver, "manifest": msize, "metasig": msig, "size": len(d), "parts": {}}
    for p in m.partitions:
        h = p.new_partition_info.hash.hex() if (p.HasField("new_partition_info") and p.new_partition_info.HasField("hash")) else ""
        out["parts"][p.partition_name] = h
    out["boot"] = out["parts"].get("boot", "")
    return out

TARGETS = {"1087678f4926673ea93d5561f207bd5ac59afc5e315aceed26dd28be4c912550": "Sept6 bundle",
           "3654956915782bfc95f098969f3acbcc0840427b8ce57c8e850ff2fc75823e77": "Sept7 bundle",
           "08f9e86065b39a8d3d67a8b475ce51d12d2a9a55ecce3cb5288ab17c212c876a": "our output",
           "43e1f3d80b12f4972cedc9d6bcadc8e8b79ddf448b3740d65a84787d17d64091": "our candidate_hook",
           "380a915f9e5bd4c4d1147d1be7ebb211d6afa41891748b4e373aeb848c4f7081": "orig factory boot"}

SEARCH_ROOTS = [r"D:/apps", r"D:/", r"E:/"]
seen = set()
found = []
for root in SEARCH_ROOTS:
    if not os.path.isdir(root): continue
    for dirpath, dirnames, filenames in os.walk(root):
        # prune heavy/irrelevant
        depth = dirpath[len(root):].count(os.sep)
        if depth > 6:
            dirnames[:] = []; continue
        dirnames[:] = [d for d in dirnames if d not in ("jadx","node_modules",".git",".venv","Windows","$Recycle.Bin","$WinREAgent","Config.Msi","ProgramData","Program Files","Program Files (x86)","Program Files (x86)","Users")]
        for fn in filenames:
            if not (fn.endswith(".bin") or fn.endswith(".zip") or fn.endswith(".img")):
                continue
            p = os.path.join(dirpath, fn)
            if p in seen: continue
            seen.add(p)
            # only inspect files that look like payloads/zips of plausible size
            try: sz = os.path.getsize(p)
            except Exception: continue
            if sz < 1_000_000 or sz > 300_000_000: continue
            try:
                if fn.endswith(".zip"):
                    zf = zipfile.ZipFile(p)
                    hits = [n for n in zf.namelist() if n.endswith("payload.bin")]
                    if not hits: continue
                    for n in hits:
                        try: r = inspect_bytes(zf.read(n))
                        except Exception: r = None
                        if r: found.append((p + "::" + n, r))
                else:
                    with open(p, "rb") as f: head = f.read(64)
                    if head[:4] != b"CrAU": continue
                    r = inspect_bytes(open(p, "rb").read())
                    if r: found.append((p, r))
            except Exception:
                continue

print(f"\n=== scanned {len(seen)} candidate files, {len(found)} payloads ===")
for label, r in found:
    marks = []
    for t, name in TARGETS.items():
        if r["boot"] == t: marks.append(f"BOOT=={name}")
    print(f"  size={r['size']:>10} manifest={r['manifest']:>6} metasig={r['metasig']:>4} boot={r['boot'][:32]} {' | '.join(marks)}  {label}")
