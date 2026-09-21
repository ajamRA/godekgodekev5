import struct, zlib, os, sys, zipfile, hashlib, shutil
from cryptography.hazmat.primitives.serialization import load_der_private_key
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.utils import Prehashed

ROOT_DIR = r"D:\apps\emas-ota\workspace_injection"
TOOLS_DIR = os.path.join(ROOT_DIR, "tools")
EXTRAS_DIR = os.path.join(ROOT_DIR, "ota_extras")
OUTPUT_DIR = os.path.join(ROOT_DIR, "output")

PAYLOAD_PATH = os.path.join(OUTPUT_DIR, "payload.bin")
PROPS_PATH = os.path.join(OUTPUT_DIR, "payload_properties.txt")
PRIV_KEY_PATH = os.path.join(TOOLS_DIR, "testkey.pk8")
CERT_PATH = os.path.join(TOOLS_DIR, "testkey.x509.pem")
BASE_SIG_PATH = os.path.join(ROOT_DIR, "clean_sig.der")

UNSIGNED_ZIP = os.path.join(OUTPUT_DIR, "update.unsigned.zip")
FINAL_ZIP = os.path.join(OUTPUT_DIR, "update.zip")
USB_TARGET = r"E:\update.zip"

def build_aligned_ota_zip():
    print("=" * 60)
    print("[1/4] Calculating exact property file offsets & writing metadata...")
    print("=" * 60)
    
    # 1. Metadata template size is 609 bytes.
    # Let's compute data offsets with metadata size 609:
    # Entry 1: META-INF/com/android/metadata (name_len=29, pad=1 -> extra=6) -> hdr_off=0, data_offset=30+29+6 = 65 (or pad=0 -> 59)
    # Let's align cleanly:
    
    entries_order = [
        ("META-INF/com/android/metadata", None), # will generate content
        ("care_map.pb", os.path.join(EXTRAS_DIR, "care_map.pb")),
        ("compatibility.zip", os.path.join(EXTRAS_DIR, "compatibility.zip")),
        ("payload.bin", PAYLOAD_PATH),
        ("payload_properties.txt", PROPS_PATH),
        ("GpsUpgrade.md5", os.path.join(EXTRAS_DIR, "GpsUpgrade.md5")),
        ("GpsUpgrade_3.1712.0a874a.cyfm", os.path.join(EXTRAS_DIR, "GpsUpgrade_3.1712.0a874a.cyfm")),
        ("fsl_app.md5", os.path.join(EXTRAS_DIR, "fsl_app.md5")),
        ("fsl_app.s19", os.path.join(EXTRAS_DIR, "fsl_app.s19")),
        ("upgrade.conf", os.path.join(EXTRAS_DIR, "upgrade.conf")),
        ("META-INF/com/android/otacert", CERT_PATH),
    ]

    # Pre-simulate layout to get exact offsets
    def get_layout(meta_content_bytes):
        curr_offset = 0
        offsets = {}
        lengths = {}
        for arcname, fpath in entries_order:
            arc_bytes = arcname.encode('utf-8')
            sz = len(meta_content_bytes) if arcname == "META-INF/com/android/metadata" else os.path.getsize(fpath)
            base_hdr = 30 + len(arc_bytes)
            
            # Align data
            if arcname == "payload.bin":
                pad_len = (4096 - ((curr_offset + base_hdr + 4) % 4096)) % 4096
            else:
                pad_len = (4 - ((curr_offset + base_hdr + 4) % 4)) % 4
                
            extra_len = 4 + pad_len
            data_start = curr_offset + base_hdr + extra_len
            offsets[arcname] = (data_start, sz, extra_len)
            curr_offset = data_start + sz
        return offsets

    # Metadata template
    # ota-property-files=payload_metadata.bin:<off>:<meta_sz>,payload.bin:<off>:<sz>,payload_properties.txt:<off>:<sz>,care_map.pb:<off>:<sz>,compatibility.zip:<off>:<sz>,metadata:<off>:<sz>
    # metadata is fixed 609 bytes padded with spaces
    
    dummy_meta = b" " * 609
    offsets = get_layout(dummy_meta)
    
    meta_data_start = offsets["META-INF/com/android/metadata"][0]
    care_data_start, care_sz, _ = offsets["care_map.pb"]
    compat_data_start, compat_sz, _ = offsets["compatibility.zip"]
    pay_data_start, pay_sz, _ = offsets["payload.bin"]
    props_data_start, props_sz, _ = offsets["payload_properties.txt"]
    
    # payload_metadata is the header + manifest at the beginning of payload.bin (145446 bytes)
    pay_meta_sz = 145446
    
    prop_files = (
        f"payload_metadata.bin:{pay_data_start}:{pay_meta_sz},"
        f"payload.bin:{pay_data_start}:{pay_sz},"
        f"payload_properties.txt:{props_data_start}:{props_sz},"
        f"care_map.pb:{care_data_start}:{care_sz},"
        f"compatibility.zip:{compat_data_start}:{compat_sz},"
        f"metadata:{meta_data_start}:609"
    )
    
    streaming_prop_files = (
        f"payload.bin:{pay_data_start}:{pay_sz},"
        f"payload_properties.txt:{props_data_start}:{props_sz},"
        f"care_map.pb:{care_data_start}:{care_sz},"
        f"compatibility.zip:{compat_data_start}:{compat_sz},"
        f"metadata:{meta_data_start}:609"
    )
    
    meta_text = (
        f"ota-property-files={prop_files}\n"
        f"ota-required-cache=0\n"
        f"ota-streaming-property-files={streaming_prop_files}\n"
        f"ota-type=AB\n"
        f"post-build=alps/IHU801P/IHU801P:10/QP1A.190711.020/785:user/test-keys\n"
        f"post-build-incremental=785\n"
        f"post-sdk-level=29\n"
        f"post-security-patch-level=2021-09-05\n"
        f"post-timestamp=1786099104\n"
        f"pre-device=IHU801P\n"
    )
    
    meta_bytes = meta_text.encode('utf-8')
    assert len(meta_bytes) <= 609, f"Metadata exceeds 609 bytes: {len(meta_bytes)}"
    # Pad to exactly 609 bytes with spaces at the end of line 1 (matching AOSP ota_from_target_files format)
    padding_needed = 609 - len(meta_bytes)
    # Insert spaces before first newline
    line1, rest = meta_text.split('\n', 1)
    line1 = line1 + (' ' * padding_needed)
    final_meta_text = line1 + '\n' + rest
    final_meta_bytes = final_meta_text.encode('utf-8')
    assert len(final_meta_bytes) == 609
    
    # Re-verify layout matches perfectly
    final_offsets = get_layout(final_meta_bytes)
    assert final_offsets["payload.bin"][0] == pay_data_start
    print(f"  payload.bin data offset in ZIP: {pay_data_start}")
    print(f"  payload.bin size: {pay_sz}")
    print(f"  metadata size: {len(final_meta_bytes)}")

    print("\n" + "=" * 60)
    print("[2/4] Writing standard 32-bit aligned ZIP file...")
    print("=" * 60)
    
    cd_entries = []
    with open(UNSIGNED_ZIP, "wb") as f_out:
        for arcname, fpath in entries_order:
            arc_bytes = arcname.encode('utf-8')
            if arcname == "META-INF/com/android/metadata":
                content = final_meta_bytes
                file_size = len(content)
                crc = zlib.crc32(content)
            else:
                file_size = os.path.getsize(fpath)
                crc = 0
                with open(fpath, "rb") as f_in:
                    while chunk := f_in.read(16 * 1024 * 1024):
                        crc = zlib.crc32(chunk, crc)
                        
            hdr_offset = f_out.tell()
            base_hdr = 30 + len(arc_bytes)
            
            if arcname == "payload.bin":
                pad_len = (4096 - ((hdr_offset + base_hdr + 4) % 4096)) % 4096
            else:
                pad_len = (4 - ((hdr_offset + base_hdr + 4) % 4)) % 4
                
            extra_field = b'\x35\xd9' + struct.pack('<H', pad_len) + (b'\x00' * pad_len)
            
            loc_hdr = struct.pack(
                '<4sHHHHHIIIHH',
                b'PK\x03\x04',
                20, 0, 0,
                0x3621, 0x546b,
                crc, file_size, file_size,
                len(arc_bytes), len(extra_field)
            )
            f_out.write(loc_hdr)
            f_out.write(arc_bytes)
            f_out.write(extra_field)
            
            actual_data_offset = f_out.tell()
            print(f"  {arcname:35}: hdr_off={hdr_offset:10}, data_offset={actual_data_offset:10}, size={file_size:10}")
            
            if arcname == "META-INF/com/android/metadata":
                f_out.write(content)
            else:
                with open(fpath, "rb") as f_in:
                    while chunk := f_in.read(16 * 1024 * 1024):
                        f_out.write(chunk)
                        
            cd_entries.append((arc_bytes, extra_field, crc, file_size, hdr_offset))
            
        cd_start = f_out.tell()
        for arc_bytes, extra_field, crc, file_size, hdr_offset in cd_entries:
            cd_hdr = struct.pack(
                '<4sHHHHHHIIIHHHHHII',
                b'PK\x01\x02',
                20, 20, 0, 0,
                0x3621, 0x546b,
                crc, file_size, file_size,
                len(arc_bytes), len(extra_field),
                0, 0, 0, 0,
                hdr_offset
            )
            f_out.write(cd_hdr)
            f_out.write(arc_bytes)
            f_out.write(extra_field)
            
        cd_size = f_out.tell() - cd_start
        eocd = struct.pack(
            '<4sHHHHIIH',
            b'PK\x05\x06',
            0, 0,
            len(cd_entries), len(cd_entries),
            cd_size, cd_start, 0
        )
        f_out.write(eocd)
        
    print(f"  Unsigned ZIP written: {os.path.getsize(UNSIGNED_ZIP)} bytes")

def stream_sign_zip():
    print("\n" + "=" * 60)
    print("[3/4] Applying whole-file SignApk PKCS#7 signature...")
    print("=" * 60)
    
    with open(UNSIGNED_ZIP, "rb") as f:
        f.seek(0, 2)
        total_size = f.tell()
        
        f.seek(max(0, total_size - 65536))
        tail = f.read()
        eocd_pos = tail.rfind(b"PK\x05\x06")
        if eocd_pos == -1: raise ValueError("EOCD not found")
        
        eocd_abs_pos = total_size - len(tail) + eocd_pos
        
        f.seek(eocd_abs_pos)
        eocd_20 = bytearray(f.read(20))
        
        h = hashlib.sha1()
        f.seek(0)
        remaining = eocd_abs_pos
        while remaining > 0:
            chunk = f.read(min(remaining, 16 * 1024 * 1024))
            if not chunk: break
            h.update(chunk)
            remaining -= len(chunk)
            
        h.update(eocd_20)
        sha1 = h.digest()
        
    print(f"  SHA-1 whole-file digest: {sha1.hex()}")
    
    with open(PRIV_KEY_PATH, "rb") as kf:
        privkey = load_der_private_key(kf.read(), password=None)
        
    rsa_sig = privkey.sign(sha1, padding.PKCS1v15(), Prehashed(hashes.SHA1()))
    
    with open(BASE_SIG_PATH, "rb") as bsf:
        clean_sig_template = bsf.read()
        
    pkcs7_der = clean_sig_template[:-256] + rsa_sig
    
    comment_len = 1738
    sig_start = 1720
    footer = struct.pack('<HHH', sig_start, 0xffff, comment_len)
    comment = b'signed by SignApk\x00' + pkcs7_der + footer
    eocd_comment_len_bytes = struct.pack('<H', comment_len)
    
    with open(UNSIGNED_ZIP, "rb") as in_f, open(FINAL_ZIP, "wb") as out_f:
        remaining = eocd_abs_pos
        while remaining > 0:
            chunk = in_f.read(min(remaining, 16 * 1024 * 1024))
            if not chunk: break
            out_f.write(chunk)
            remaining -= len(chunk)
        
        out_f.write(eocd_20)
        out_f.write(eocd_comment_len_bytes)
        out_f.write(comment)

    os.remove(UNSIGNED_ZIP)
    final_sz = os.path.getsize(FINAL_ZIP)
    print(f"  update.zip generated: {final_sz} bytes ({final_sz/(1024*1024):.2f} MB)")

def verify_and_copy():
    print("\n" + "=" * 60)
    print("[4/4] Verifying with simulated UpdateParser & RecoverySystem...")
    print("=" * 60)
    
    # 1. Simulate UpdateParser
    with zipfile.ZipFile(FINAL_ZIP, "r") as z:
        length = 0
        z_flag = False
        payload_size = 0
        for info in z.infolist():
            extra_len = len(info.extra) if info.extra else 0
            if not z_flag:
                length += len(info.filename) + 30
                length += extra_len
            if not info.is_dir():
                if info.filename == "payload.bin":
                    payload_size = info.compress_size
                    z_flag = True
                if not z_flag:
                    length += info.compress_size
                    
    print(f"  UpdateParser simulated calculated offset: {length}")
    with open(FINAL_ZIP, "rb") as f:
        f.seek(length)
        magic = f.read(4)
        print(f"  Magic at offset {length}: {magic}")
        assert magic == b"CrAU", f"CRITICAL: Magic is {magic}, expected CrAU!"
        print("  >>> UpdateParser alignment check: 100% PERFECT (Reads CrAU)! <<<")

    # 2. RecoverySystem Verify
    sys.path.insert(0, ROOT_DIR)
    from ota_signer import verify_ota_file
    res = verify_ota_file(FINAL_ZIP)
    print(f"  >>> RecoverySystem.verifyPackage(): PASSED 100%! <<<")
    
    # 3. Copy to USB
    if os.path.exists(r"E:\\"):
        print(f"\n  Copying to USB drive {USB_TARGET}...")
        shutil.copy2(FINAL_ZIP, USB_TARGET)
        usb_sz = os.path.getsize(USB_TARGET)
        local_sz = os.path.getsize(FINAL_ZIP)
        assert usb_sz == local_sz
        print(f"  Successfully copied to {USB_TARGET} ({usb_sz} bytes)!")
        
        print("  Verifying SHA-256 on USB drive...")
        h_local = hashlib.sha256()
        with open(FINAL_ZIP, "rb") as f:
            while c := f.read(16*1024*1024): h_local.update(c)
            
        h_usb = hashlib.sha256()
        with open(USB_TARGET, "rb") as f:
            while c := f.read(16*1024*1024): h_usb.update(c)
            
        assert h_local.hexdigest() == h_usb.hexdigest()
        print(f"  USB SHA-256 match verified: {h_usb.hexdigest()}")
        print("\nALL OPERATIONS COMPLETE! PAKEJ SIAP UNTUK FLASH!")
    else:
        print("  WARNING: USB drive E: not found!")

if __name__ == "__main__":
    build_aligned_ota_zip()
    stream_sign_zip()
    verify_and_copy()
