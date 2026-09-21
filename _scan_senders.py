import zipfile, glob, os

PLAIN = b"intent.action.UPDATE_BROADCAST"          # akan padan juga _FACTORY
FACT  = b"intent.action.UPDATE_BROADCAST_FACTORY"

rows = []
for apk in sorted(glob.glob("**/*.apk", recursive=True)):
    try:
        z = zipfile.ZipFile(apk)
    except Exception as e:
        rows.append((apk, "ERR", str(e)))
        continue
    p = f = 0
    for n in z.namelist():
        if n.endswith(".dex"):
            d = z.read(n)
            p += d.count(PLAIN)
            f += d.count(FACT)
    if p or f:
        rows.append((apk, p, f))

print("%-58s %8s %8s" % ("APK", "BROADCAST", "_FACTORY"))
print("-" * 78)
for apk, p, f in rows:
    print("%-58s %8s %8s" % (apk[:58], p, f))
print()
print("jumlah APK diimbas:", len(glob.glob("**/*.apk", recursive=True)))
