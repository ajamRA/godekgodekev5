"""
Advanced boot.img patcher for Geely EX2 (IHU801P):
1. Injects mount hook into system/bin/init to bind-mount /first_stage_ramdisk/prop.default over /system/etc/prop.default.
2. Bypasses /force_debuggable and /debug_ramdisk/adb_debug.prop checks in init.
3. Injects /force_debuggable and /debug_ramdisk/adb_debug.prop into ramdisk.
4. Updates prop.default with ro.debuggable=1, ro.secure=0, userdebug build, ADB on TCP 5555 and USB.
5. Updates kernel cmdline in boot.img header to include buildvariant=userdebug androidboot.debuggable=1 androidboot.force_normal_boot=1.
6. Correctly preserves recovery_dtbo and pads to full 33,554,432 bytes partition size.
"""

import os
import struct
import gzip
import capstone

ORIG_BOOT = r"D:\apps\emas-ota\workspace_injection\original_backup\boot.img"
PATCHED_DIR = r"D:\apps\emas-ota\workspace_injection\patched_build"
OUT_BOOT = os.path.join(PATCHED_DIR, "boot.img")


# Verified SAR handoff: support entries exist at root and first_stage_ramdisk/
SAR_HANDOFF_VERIFIED = True


def patch_property_entry(entry):
    """Preserve CPIO symlinks; modify only regular property files."""
    mode = int(entry["hdr"][14:22], 16)
    if mode & 0o170000 == 0o120000:
        if entry["name"] != "default.prop" or entry["data"] != b"prop.default":
            raise ValueError("Unexpected property symlink; refusing to alter it")
        return False
    if mode & 0o170000 != 0o100000:
        raise ValueError("Property entry must be a regular file or known symlink")
    entry["data"] = patch_prop_text(entry["data"])
    entry["hdr"][54:62] = f"{len(entry['data']):08x}".encode("ascii")
    return True


def make_support_entry(template_hdr, name, data, prefix="first_stage_ramdisk/"):
    """Place support files in both root and first_stage_ramdisk subtrees."""
    if name not in ("p", "w"):
        raise ValueError("Unsupported injection file")
    path = (prefix + name) if prefix else name
    return {"hdr": make_file_hdr(template_hdr, len(data), len(path.encode()) + 1),
            "name": path, "data": data}


def build_hook():
    """Use the statically checked 152-byte encoder, with aligned literals."""
    from model_hook_176 import build, check
    check()
    blob, code_size, addresses = build()
    assert len(blob) == 152 and code_size == 80
    return blob


def patch_init_binary(init_raw: bytes) -> bytes:
    init_mod = bytearray(init_raw)

    NOP = bytes([0x1F, 0x20, 0x03, 0xD5])
    # 1. 0x3ebc8: bypass /force_debuggable check
    init_mod[0x3EBC8 : 0x3EBCC] = NOP
    # 2. 0x5bda8: bypass debuggable check when loading /debug_ramdisk/adb_debug.prop
    init_mod[0x5BDA8 : 0x5BDAC] = NOP
    # 3. 0x5c77c: mov w19, #1 (force is_debuggable = 1 in property service)
    init_mod[0x5C77C : 0x5C780] = bytes([0x33, 0x00, 0x80, 0x52])

    # 3b. Redirect /system/etc/prop.default -> /first_stage_ramdisk/p (write-once ro.* load order)
    orig_prop_path = b"/system/etc/prop.default\x00"
    new_prop_path = b"/first_stage_ramdisk/p\x00".ljust(len(orig_prop_path), b"\x00")
    assert init_mod[0x136E4 : 0x136E4 + len(orig_prop_path)] == orig_prop_path, (
        f"Expected /system/etc/prop.default at 0x136E4, got {init_mod[0x136E4:0x136E4+32]!r}"
    )
    init_mod[0x136E4 : 0x136E4 + len(orig_prop_path)] = new_prop_path
    print("Patched init string 0x136E4: /system/etc/prop.default -> /first_stage_ramdisk/p")

    # 4. Verify original instruction at 0x6b6c8: b.ne #0x6b6d8 (0x54000081)
    orig_bne = struct.unpack_from("<I", init_mod, 0x6B6C8)[0]
    assert orig_bne == 0x54000081, f"Expected 0x54000081 at 0x6b6c8, got {hex(orig_bne)}"

    # 5. Patch 0x6b6c8 to branch to 0xa9f50
    hook_addr = 0xA9F50
    bne_diff = hook_addr - 0x6B6C8
    bne_imm19 = (bne_diff >> 2) & 0x7FFFF
    bne_opcode = 0x54000001 | (bne_imm19 << 5)
    struct.pack_into("<I", init_mod, 0x6B6C8, bne_opcode)

    # 6. Inject mount hook at 0xa9f50
    hook_bytes = build_hook()
    assert (
        init_mod[hook_addr : hook_addr + len(hook_bytes)]
        == b"\x00" * len(hook_bytes)
    ), "Hook region is not zeroes!"
    init_mod[hook_addr : hook_addr + len(hook_bytes)] = hook_bytes

    # 7. Verify with Capstone
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    branch_insn = list(md.disasm(init_mod[0x6B6C8 : 0x6B6CC], 0x6B6C8))[0]
    print(f"Patched 0x6b6c8: {branch_insn.mnemonic} {branch_insn.op_str}")
    assert "0xa9f50" in branch_insn.op_str

    print("Disassembly of injected hook at 0xa9f50:")
    for insn in md.disasm(hook_bytes[:80], hook_addr):
        print(f"  {hex(insn.address)}: {insn.mnemonic} {insn.op_str}")

    return bytes(init_mod)


def patch_prop_text(orig: bytes) -> bytes:
    text = orig.replace(b"\x00", b"").decode("latin1")
    lines = text.splitlines()
    target_props = {
        "ro.secure": "0",
        "ro.adb.secure": "0",
        "ro.debuggable": "1",
        "ro.build.type": "userdebug",
        "ro.system.build.type": "userdebug",
        "ro.vendor.build.type": "userdebug",
        "ro.odm.build.type": "userdebug",
        "ro.product.build.type": "userdebug",
        "persist.adb.tcp.port": "5555",
        "service.adb.tcp.port": "5555",
        "persist.service.adb.tcp.port": "5555",
    }
    out = []
    seen = set()
    for line in lines:
        stripped = line.strip()
        if "=" in stripped and not stripped.startswith("#"):
            k, _ = stripped.split("=", 1)
            k = k.strip()
            if k in target_props:
                out.append(f"{k}={target_props[k]}")
                seen.add(k)
                continue
        out.append(line)
    for k, v in target_props.items():
        if k not in seen:
            out.append(f"{k}={v}")
    body = "\n".join(out).rstrip() + "\n"
    return body.encode("latin1")


def make_file_hdr(base_hdr: bytes, filesize: int, namesize: int) -> bytearray:
    """Create a CPIO header with regular file mode (S_IFREG | 0644 = 0o100644)."""
    hdr = bytearray(base_hdr)
    hdr[0:6] = b"070701"
    hdr[14:22] = b"000081a4"  # S_IFREG | 0644 (mode 0o100644)
    hdr[22:30] = b"00000000"  # uid 0
    hdr[30:38] = b"00000000"  # gid 0
    hdr[38:46] = b"00000001"  # nlink 1
    hdr[46:54] = b"00000000"  # mtime 0
    hdr[54:62] = f"{filesize:08x}".encode("ascii")
    hdr[62:70] = b"00000000"  # maj 0
    hdr[70:78] = b"00000000"  # min 0
    hdr[78:86] = b"00000000"  # rmaj 0
    hdr[86:94] = b"00000000"  # rmin 0
    hdr[94:102] = f"{namesize:08x}".encode("ascii")
    hdr[102:110] = b"00000000"  # chksum 0
    return hdr


def main(*, verification_candidate=False):
    if not SAR_HANDOFF_VERIFIED and not verification_candidate:
        raise SystemExit("BUILD BLOCKED: SAR file preservation and checked mount/log hook not implemented; existing images untouched")
    print("Reading original boot.img...")
    with open(ORIG_BOOT, "rb") as f:
        orig_img = f.read()

    assert orig_img[:8] == b"ANDROID!"
    assert len(orig_img) == 33554432, f"Expected 33554432 bytes, got {len(orig_img)}"

    ksz = struct.unpack_from("<I", orig_img, 8)[0]
    rsz = struct.unpack_from("<I", orig_img, 16)[0]
    page_size = struct.unpack_from("<I", orig_img, 36)[0]

    koff = page_size
    kernel_pages = (ksz + page_size - 1) // page_size * page_size
    roff = koff + kernel_pages

    kernel_bytes = orig_img[koff : koff + ksz]
    ramdisk_gz = orig_img[roff : roff + rsz]

    print(f"Decompressing ramdisk ({rsz} bytes gz)...")
    cpio_raw = gzip.decompress(ramdisk_gz)

    # Parse CPIO entries
    entries = []
    i = 0
    n = len(cpio_raw)
    template_hdr = None
    while i + 110 <= n:
        mag = cpio_raw[i : i + 6]
        if mag not in (b"070701", b"070702"):
            nxt = cpio_raw.find(b"070701", i)
            if nxt == -1 or nxt - i > 8192:
                break
            i = nxt
            continue
        e_hdr = cpio_raw[i : i + 110]
        namesize = int(e_hdr[94:102], 16)
        filesize = int(e_hdr[54:62], 16)
        name_off = i + 110
        name = (
            cpio_raw[name_off : name_off + namesize]
            .split(b"\x00", 1)[0]
            .decode("utf-8", "replace")
        )
        if template_hdr is None or name == "prop.default":
            template_hdr = e_hdr
        data_off = (name_off + namesize + 3) // 4 * 4
        data = cpio_raw[data_off : data_off + filesize]
        entries.append({"hdr": bytearray(e_hdr), "name": name, "data": data})
        i = (data_off + filesize + 3) // 4 * 4
        if name == "TRAILER!!!":
            break

    print(f"Parsed {len(entries)} CPIO entries.")

    # Patch system/bin/init, prop.default, default.prop, and init.rc
    patched_init_count = 0
    patched_prop_count = 0
    orig_system_prop_path = r"D:\apps\emas-ota\extract\system.build.prop"
    with open(orig_system_prop_path, "rb") as f:
        orig_system_prop = f.read()
    system_build_prop_patched = patch_prop_text(orig_system_prop)

    wifi_rc_content = b"""on early-init
    setprop service.adb.tcp.port 5555
    setprop persist.adb.tcp.port 5555
    setprop persist.service.adb.tcp.port 5555

on init
    setprop service.adb.tcp.port 5555
    setprop persist.adb.tcp.port 5555
    setprop persist.service.adb.tcp.port 5555

on boot
    setprop service.adb.tcp.port 5555
    setprop persist.adb.tcp.port 5555
    setprop persist.service.adb.tcp.port 5555
    start adbd

on property:sys.boot_completed=1
    setprop service.adb.tcp.port 5555
    setprop persist.adb.tcp.port 5555
    setprop persist.service.adb.tcp.port 5555
    start adbd
    exec -- /system/bin/settings put global adb_enabled 1
    exec -- /system/bin/settings put secure adb_enabled 1

service blank_screen /system/bin/blank_screen
    disabled
    oneshot
"""

    for e in entries:
        if e["name"] == "system/bin/init":
            print("Patching system/bin/init with mount hook & NOPs...")
            e["data"] = patch_init_binary(e["data"])
            patched_init_count += 1
        elif e["name"] in ("prop.default", "default.prop"):
            print(f"Patching {e['name']}...")
            patched_prop_count += int(patch_property_entry(e))
        elif e["name"] == "init.rc":
            print("Patching ramdisk init.rc for automatic Wi-Fi adbd...")
            rc_text = e["data"].decode("latin1")
            rc_extra = """
on boot
    setprop service.adb.tcp.port 5555
    setprop persist.adb.tcp.port 5555
    start adbd

on property:sys.boot_completed=1
    setprop service.adb.tcp.port 5555
    setprop persist.adb.tcp.port 5555
    start adbd
    exec -- /system/bin/settings put global adb_enabled 1
    exec -- /system/bin/settings put secure adb_enabled 1
"""
            new_rc = (rc_text + rc_extra).encode("latin1")
            e["data"] = new_rc
            e["hdr"][54:62] = f"{len(new_rc):08x}".encode("ascii")
            print("Patched init.rc with Wi-Fi adbd auto-start.")

    assert patched_init_count == 1, "Failed to patch system/bin/init!"
    assert patched_prop_count >= 1, "Failed to patch prop.default!"

    # Inject p (system.build.prop) and w (wifi_adb.rc for blank_screen.rc mount)
    # Inject BOTH at root ('/p', '/w') and in ('/first_stage_ramdisk/p', '/first_stage_ramdisk/w')
    entries.insert(0, make_support_entry(template_hdr, "p", system_build_prop_patched, prefix=""))
    entries.insert(1, make_support_entry(template_hdr, "w", wifi_rc_content, prefix=""))
    entries.insert(2, make_support_entry(template_hdr, "p", system_build_prop_patched, prefix="first_stage_ramdisk/"))
    entries.insert(3, make_support_entry(template_hdr, "w", wifi_rc_content, prefix="first_stage_ramdisk/"))
    print("Injected 'p'/'w' at root and first_stage_ramdisk/ for dual-mount hook (mode 0o100644)")

    # Inject force_debuggable and debug_ramdisk/adb_debug.prop
    hdr_fd = make_file_hdr(template_hdr, 0, len(b"force_debuggable\x00"))
    entries.insert(4, {"hdr": hdr_fd, "name": "force_debuggable", "data": b""})

    adb_prop_content = b"""ro.debuggable=1
ro.secure=0
ro.adb.secure=0
ro.build.type=userdebug
ro.system.build.type=userdebug
ro.vendor.build.type=userdebug
ro.odm.build.type=userdebug
ro.product.build.type=userdebug
service.adb.tcp.port=5555
persist.adb.tcp.port=5555
persist.service.adb.tcp.port=5555
"""
    hdr_ad = make_file_hdr(template_hdr, len(adb_prop_content), len(b"debug_ramdisk/adb_debug.prop\x00"))
    entries.insert(3, {"hdr": hdr_ad, "name": "debug_ramdisk/adb_debug.prop", "data": adb_prop_content})
    print("Injected force_debuggable and debug_ramdisk/adb_debug.prop (mode 0o100644)")

    # Re-pack CPIO
    print("Re-packing CPIO archive...")
    out_cpio = bytearray()
    for e in entries:
        name_b = e["name"].encode("utf-8") + b"\x00"
        data_b = e["data"]
        hdr_b = bytearray(e["hdr"])
        hdr_b[54:62] = f"{len(data_b):08x}".encode("ascii")
        hdr_b[94:102] = f"{len(name_b):08x}".encode("ascii")
        out_cpio += hdr_b
        out_cpio += name_b
        while len(out_cpio) % 4:
            out_cpio += b"\x00"
        out_cpio += data_b
        while len(out_cpio) % 4:
            out_cpio += b"\x00"

    print(f"Compressing new ramdisk ({len(out_cpio)} bytes CPIO)...")
    new_ramdisk_gz = gzip.compress(bytes(out_cpio), compresslevel=9, mtime=0)
    print(f"New ramdisk gz size: {len(new_ramdisk_gz)} bytes.")

    # Prepare updated boot header
    header = bytearray(orig_img[:page_size])

    # 1. Update ramdisk size
    struct.pack_into("<I", header, 16, len(new_ramdisk_gz))

    # 2. Update kernel command line in header
    new_cmdline = b"bootopt=64S3,32N2,64N2 buildvariant=userdebug androidboot.debuggable=1 androidboot.force_normal_boot=1"
    assert len(new_cmdline) < 512, "Cmdline too long!"
    header[64 : 64 + 512] = new_cmdline.ljust(512, b"\x00")
    print(f"Updated kernel cmdline: {new_cmdline.decode('ascii')}")

    # Build final boot.img matching original structure exactly
    out = bytearray()
    out += header
    out += kernel_bytes
    out += b"\x00" * ((page_size - (len(kernel_bytes) % page_size)) % page_size)
    out += new_ramdisk_gz
    out += b"\x00" * ((page_size - (len(new_ramdisk_gz) % page_size)) % page_size)

    # Preserve recovery_dtbo if present in original
    rec_dtbo_sz, rec_dtbo_off = struct.unpack("<IQ", orig_img[1632:1644])
    if rec_dtbo_off > 0 and rec_dtbo_off < len(orig_img):
        if len(out) > rec_dtbo_off:
            raise SystemExit(f"ramdisk overflowed into DTBO: {len(out)} > {rec_dtbo_off}")
        out += b"\x00" * (rec_dtbo_off - len(out))
        out += orig_img[rec_dtbo_off:]

    # Pad out to full partition size (33,554,432 bytes)
    if len(out) < len(orig_img):
        out += b"\x00" * (len(orig_img) - len(out))

    assert len(out) == 33554432, f"Final boot image size mismatch: {len(out)}"

    os.makedirs(PATCHED_DIR, exist_ok=True)
    with open(OUT_BOOT, "wb") as f:
        f.write(out)

    print(f"Successfully generated {OUT_BOOT} ({len(out)} bytes).")


if __name__ == "__main__":
    main()
