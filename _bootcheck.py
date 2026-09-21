import zipfile, re, struct, io, os

MARK = [b"ro.debuggable=1", b"adb.tcp.port", b"start adbd", b"service.adb.tcp.port",
        b"persist.adb.tcp.port", b"ro.adb.secure=0", b"adb_enabled", b"prop.default",
        b"blank_screen", b"userdebug", b"ro.secure=0"]

def scan_boot(name, data):
    print(f"\n### {name}  size={len(data)}")
    if data[:8] != b"ANDROID!":
        print("   not ANDROID boot image"); return
    kv = struct.unpack("<8I", data[8:40])
    kernel_size, ramdisk_size, second_size = kv[1], kv[2], kv[3]
    page = kv[7]
    print(f"   kernel={kernel_size} ramdisk={ramdisk_size} second={second_size} page={page}")
    n = 1 + (kernel_size + page - 1)//page
    rd = data[n*page: n*page + ramdisk_size]
    print(f"   ramdisk bytes={len(rd)} first4={rd[:4]!r}")
    hits = {m.decode(): rd.count(m) for m in MARK if rd.count(m)}
    print("   MARKERS in ramdisk:", hits if hits else "NONE")
    # also search whole image
    allhits = {m.decode(): data.count(m) for m in MARK if data.count(m)}
    print("   MARKERS in whole image:", allhits if allhits else "NONE")

for z in ["workspace_injection/output/update.zip", "workspace_injection/output/update.unsigned.zip",
          "workspace_injection/old_output/update.zip", "workspace_injection/revert_package/update_revert.zip"]:
    if not os.path.exists(z):
        print("\n### missing", z); continue
    zf = zipfile.ZipFile(z)
    if "boot.img" in zf.namelist():
        scan_boot(z + "::boot.img", zf.read("boot.img"))
    # payload-only packages: extract boot from payload? skip
print()

print("### plain boot.imgs")
for p in ["parts/boot.img", "boot.img", "workspace_injection/boot.img"]:
    if os.path.exists(p):
        scan_boot(p, open(p, "rb").read())
