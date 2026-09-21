import os, shutil, hashlib, time

src = r"D:\apps\emas-ota\workspace_injection\output\update.zip"
dst = r"E:\update.zip"

print("E exists", os.path.isdir("E:\\"))
if os.path.isdir("E:\\"):
    print("E listing:")
    for n in os.listdir("E:\\"):
        p = os.path.join("E:\\", n)
        kind = "<dir>" if os.path.isdir(p) else os.path.getsize(p)
        print(" ", n, kind)

print("src", os.path.getsize(src), time.strftime("%H:%M:%S", time.localtime(os.path.getmtime(src))))
shutil.copy2(src, dst)
ss = os.path.getsize(src)
ds = os.path.getsize(dst)
print("copied", ds, "match", ss == ds)

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

hs, hd = sha256(src), sha256(dst)
print("sha src", hs)
print("sha dst", hd)
print("HASH_OK" if hs == hd else "HASH_MISMATCH")
