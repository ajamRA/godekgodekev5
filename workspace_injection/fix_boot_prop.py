"""Rebuild patched boot.img with a clean prop.default (no CPIO filesize off-by-2)."""
import gzip
import os
import struct

ORIG_BOOT = r"D:\apps\emas-ota\workspace_injection\original_backup\boot.img"
OUT_BOOT = r"D:\apps\emas-ota\workspace_injection\patched_build\boot.img"


def boot_parts(img: bytes):
    assert img[:8] == b"ANDROID!"
    ksz = struct.unpack_from("<I", img, 8)[0]
    rsz = struct.unpack_from("<I", img, 16)[0]
    page = struct.unpack_from("<I", img, 36)[0]
    koff = page
    roff = page + ((ksz + page - 1) // page) * page
    return ksz, rsz, page, koff, roff, img[roff : roff + rsz]


def parse_cpio(raw: bytes):
    entries = []
    i = 0
    n = len(raw)
    while i + 110 <= n:
        mag = raw[i : i + 6]
        if mag not in (b"070701", b"070702"):
            nxt = raw.find(b"070701", i)
            if nxt == -1 or nxt - i > 8192:
                break
            i = nxt
            continue
        hdr = raw[i : i + 110]
        namesize = int(hdr[94:102], 16)
        filesize = int(hdr[54:62], 16)
        name_off = i + 110
        name = raw[name_off : name_off + namesize].split(b"\x00", 1)[0].decode("utf-8", "replace")
        data_off = (name_off + namesize + 3) // 4 * 4
        data = raw[data_off : data_off + filesize]
        entries.append({"hdr": hdr, "name": name, "data": data})
        i = (data_off + filesize + 3) // 4 * 4
        if name == "TRAILER!!!":
            break
    return entries


def pack_cpio(entries):
    out = bytearray()
    for e in entries:
        name = e["name"].encode("utf-8") + b"\x00"
        data = e["data"]
        hdr = bytearray(e["hdr"])
        hdr[54:62] = f"{len(data):08x}".encode("ascii")
        hdr[94:102] = f"{len(name):08x}".encode("ascii")
        out += hdr
        out += name
        while len(out) % 4:
            out += b"\x00"
        out += data
        while len(out) % 4:
            out += b"\x00"
    return bytes(out)


def patch_prop(orig: bytes) -> bytes:
    text = orig.replace(b"\x00", b"").decode("latin1")
    lines = text.splitlines()
    repl = {
        "ro.secure=1": "ro.secure=0",
        "ro.adb.secure=1": "ro.adb.secure=0",
        "ro.debuggable=0": "ro.debuggable=1",
        "ro.build.type=user": "ro.build.type=userdebug",
        "ro.system.build.type=user": "ro.system.build.type=userdebug",
        "ro.vendor.build.type=user": "ro.vendor.build.type=userdebug",
        "ro.odm.build.type=user": "ro.odm.build.type=userdebug",
        "ro.product.build.type=user": "ro.product.build.type=userdebug",
        "persist.sys.usb.config=none": "persist.sys.usb.config=mtp,adb",
        "persist.sys.molead.usb.mode=host": "persist.sys.molead.usb.mode=adb",
    }
    extra = [
        "persist.adb.tcp.port=5555",
        "service.adb.tcp.port=5555",
        "sys.usb.config=mtp,adb",
    ]
    out = []
    seen_extra = set()
    for line in lines:
        stripped = line.strip()
        if stripped in extra:
            seen_extra.add(stripped)
            continue
        out.append(repl.get(line, line))
    for e in extra:
        if e not in seen_extra:
            out.append(e)
    body = "\n".join(out).rstrip() + "\n"
    data = body.encode("latin1")
    if b"\x00" in data:
        raise SystemExit("null byte survived in prop.default")
    if not data.endswith(b"sys.usb.config=mtp,adb\n"):
        raise SystemExit("sys.usb.config line incomplete: " + repr(data[-40:]))
    return data


def patch_init_binary(data: bytes) -> bytes:
    b = bytearray(data)
    NOP = bytes([0x1f, 0x20, 0x03, 0xd5])
    # 1. 0x3ebc8: bypass /force_debuggable check
    b[0x3ebc8 : 0x3ebcc] = NOP
    # 2. 0x5bda8: bypass debuggable check when loading /debug_ramdisk/adb_debug.prop
    b[0x5bda8 : 0x5bdac] = NOP
    s1 = b"/prop.default\x00" + b"\x00" * 16
    b[0x13cc0 : 0x13cc0 + len(s1)] = s1
    # 4. 0x13d2d: change target to /adb_debug.prop
    s2 = b"/adb_debug.prop\x00" + b"\x00" * 14

    b[0x13d2d : 0x13d2d + len(s2)] = s2
    return bytes(b)



def write_boot(orig_img: bytes, ramdisk_gz: bytes) -> bytes:

    ksz, rsz, page, koff, roff, _old = boot_parts(orig_img)
    header = bytearray(orig_img[:page])
    struct.pack_into("<I", header, 16, len(ramdisk_gz))
    kernel = orig_img[koff : koff + ksz]
    out = bytearray()
    out += header
    out += kernel
    out += b"\x00" * ((page - (len(kernel) % page)) % page)
    out += ramdisk_gz
    out += b"\x00" * ((page - (len(ramdisk_gz) % page)) % page)

    # Read recovery_dtbo_offset from header v1/v2 (offset 1636)
    rec_dtbo_sz, rec_dtbo_off = struct.unpack("<IQ", orig_img[1632:1644])
    if rec_dtbo_off > 0 and rec_dtbo_off < len(orig_img):
        if len(out) > rec_dtbo_off:
            raise SystemExit(f"ramdisk overflowed into DTBO: {len(out)} > {rec_dtbo_off}")
        out += b"\x00" * (rec_dtbo_off - len(out))
        out += orig_img[rec_dtbo_off:]
    else:
        if len(out) > len(orig_img):
            raise SystemExit(f"boot too large {len(out)} > {len(orig_img)}")
        out += b"\x00" * (len(orig_img) - len(out))
    
    assert len(out) == len(orig_img), f"Size mismatch: {len(out)} != {len(orig_img)}"
    return bytes(out)


def main():

    orig = open(ORIG_BOOT, "rb").read()
    ksz, rsz, page, koff, roff, rgz = boot_parts(orig)
    raw = gzip.decompress(rgz)
    entries = parse_cpio(raw)
    found_prop = False
    found_init = False
    template_hdr = None

    for e in entries:
        if e["name"] == "prop.default":
            e["data"] = patch_prop(e["data"])
            found_prop = True
            template_hdr = bytearray(e["hdr"])
            print("prop.default patched:", len(e["data"]), "bytes")
        elif e["name"] == "system/bin/init":
            e["data"] = patch_init_binary(e["data"])
            found_init = True
            print("system/bin/init patched (ARM64 NOPs and string redirects applied)")

    if not found_prop:
        raise SystemExit("prop.default missing")
    if not found_init:
        raise SystemExit("system/bin/init missing")

    # Inject /force_debuggable and /debug_ramdisk/adb_debug.prop
    hdr_fd = bytearray(template_hdr)
    entries.insert(0, {"hdr": hdr_fd, "name": "force_debuggable", "data": b""})

    adb_prop_content = b"""ro.debuggable=1
ro.secure=0
ro.adb.secure=0
ro.build.type=userdebug
ro.system.build.type=userdebug
ro.vendor.build.type=userdebug
ro.odm.build.type=userdebug
ro.product.build.type=userdebug
persist.sys.usb.config=mtp,adb
persist.sys.molead.usb.mode=adb
sys.usb.config=mtp,adb
service.adb.tcp.port=5555
persist.adb.tcp.port=5555
"""
    hdr_ad = bytearray(template_hdr)
    entries.insert(1, {"hdr": hdr_ad, "name": "debug_ramdisk/adb_debug.prop", "data": adb_prop_content})
    print("Injected force_debuggable and debug_ramdisk/adb_debug.prop")

    packed = pack_cpio(entries)
    gz = gzip.compress(packed, compresslevel=9, mtime=0)
    boot = write_boot(orig, gz)
    os.makedirs(os.path.dirname(OUT_BOOT), exist_ok=True)
    open(OUT_BOOT, "wb").write(boot)
    print("wrote", OUT_BOOT, len(boot))

    # verify round-trip
    _k, _r, _p, _ko, _ro, new_gz = boot_parts(boot)
    new_raw = gzip.decompress(new_gz)
    verified = 0
    for e in parse_cpio(new_raw):
        if e["name"] == "prop.default":
            assert b"ro.build.type=userdebug" in e["data"]
            verified += 1
        elif e["name"] == "force_debuggable":
            verified += 1
        elif e["name"] == "debug_ramdisk/adb_debug.prop":
            assert b"ro.build.type=userdebug" in e["data"]
            verified += 1
        elif e["name"] == "system/bin/init":
            assert e["data"][0x3ebc8 : 0x3ebcc] == bytes([0x1f, 0x20, 0x03, 0xd5])
            assert e["data"][0x5bda8 : 0x5bdac] == bytes([0x1f, 0x20, 0x03, 0xd5])
            verified += 1

    assert verified == 4, f"Verification failed: only {verified}/4 checked"
    print(">>> VERIFICATION ALL 4 LAYERS PASSED 100%! <<<")



if __name__ == "__main__":
    main()
