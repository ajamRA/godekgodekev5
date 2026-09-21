import base64, hashlib, zipfile, glob, os, re, io

print("=== [1] Decode boot sha256 dari InstallPlan ===")
b = base64.b64decode("EIdnj0kmZz6pPVVh8ge9WsWa/F4xWs7tJt0ovkyRJVA=")
print("EIdnj0km... ->", b.hex())

KNOWN = {
 "our output boot": "08f9e86065b39a8d3d67a8b475ce51d12d2a9a55ecce3cb5288ab17c212c876a",
 "old_output boot": "baaea874",   # prefix only
 "factory boot": "380a915f",
}
for k,v in KNOWN.items():
    print(f"  {k}: {v}")

print()
print("=== [2] Cari payload.bin / update.zip & kira saiz payload + boot sha ===")
def inspect_zip(p):
    info = {"path": p, "size": os.path.getsize(p)}
    try:
        z = zipfile.ZipFile(p)
        names = z.namelist()
        pn = [n for n in names if n.endswith("payload.bin")]
        info["payload_entry"] = pn
        if pn:
            info["payload_size"] = z.getinfo(pn[0]).file_size
        for n in names:
            if n.endswith("payload_properties.txt"):
                info["props"] = z.read(n).decode(errors="replace")
        # boot.img direct?
        bi = [n for n in names if n.endswith("boot.img")]
        info["boot_entries"] = bi
    except Exception as e:
        info["err"] = str(e)
    return info

cands = set()
for pat in ["**/update.zip","**/*.zip","**/payload.bin"]:
    for x in glob.glob(pat, recursive=True):
        cands.add(x)
for p in sorted(cands):
    if any(s in p.lower() for s in ["jadx","_logs","node_modules"]):
        continue
    sz = os.path.getsize(p)
    if sz < 100000:
        continue
    print(f"\n--- {p} ({sz} B) ---")
    if p.endswith(".zip"):
        for k,v in inspect_zip(p).items():
            if k!="path":
                print(f"   {k}: {v}")
