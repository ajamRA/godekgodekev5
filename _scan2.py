import zipfile, glob, re

NEEDLES = [
    b"intent.action.UPDATE_BROADCAST\x00",   # NUL-terminated => pasti bukan _FACTORY
    b"intent.action.UPDATE_BROADCAST_FACTORY",
    b"verifyOtaPackage",
    b"IOtaVerifyService",
    b"systemUpgradeOTA",
    b"socPackageVerifier",
    b"verifyPackage",
    b"installPackage",
    b"applyPayload",
]

for apk in sorted(glob.glob("**/*.apk", recursive=True)):
    try:
        z = zipfile.ZipFile(apk)
    except Exception:
        continue
    hits = {}
    for n in z.namelist():
        if not n.endswith(".dex"):
            continue
        d = z.read(n)
        for nd in NEEDLES:
            c = d.count(nd)
            if c:
                hits.setdefault(nd.decode("ascii", "replace").rstrip("\x00"), 0)
                hits[nd.decode("ascii", "replace").rstrip("\x00")] += c
    if hits:
        print("==", apk)
        for k, v in sorted(hits.items()):
            print("     %-45s %d" % (k, v))
