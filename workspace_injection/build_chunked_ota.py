import os, sys, struct, hashlib, lzma, bz2, zipfile, io, shutil
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, utils
from cryptography.hazmat.primitives import serialization
from cryptography import x509
from payload_dumper import update_metadata_pb2
from ota_signer import sign_whole_file_ota, verify_ota_file

ROOT_DIR = r"D:\apps\emas-ota\workspace_injection"
PATCHED_BOOT = os.path.join(ROOT_DIR, r"patched_build\boot.img")
ORIGINAL_BOOT = os.path.join(ROOT_DIR, r"original_backup\boot.img")
TOOLS_DIR = os.path.join(ROOT_DIR, "tools")
EXTRAS_DIR = os.path.join(ROOT_DIR, "ota_extras")
OUTPUT_DIR = os.path.join(ROOT_DIR, "output")
USB_DIR = r"E:\\"

PRIV_KEY_PATH = os.path.join(TOOLS_DIR, "testkey.pk8")
KEY_PEM_PATH = os.path.join(TOOLS_DIR, "testkey.key.pem")
CERT_PATH = os.path.join(TOOLS_DIR, "testkey.x509.pem")
BASE_SIG_PATH = os.path.join(ROOT_DIR, "clean_sig.der")

with open(PRIV_KEY_PATH, "rb") as f:
    private_key = serialization.load_der_private_key(f.read(), password=None)

with open(CERT_PATH, "rb") as f:
    cert_bytes = f.read()

def build_chunked_payload(boot_img_path, out_payload_path, out_props_path):
    print(f"[+] Reading boot image: {boot_img_path}")
    with open(boot_img_path, "rb") as f:
        boot_raw = f.read()

    assert len(boot_raw) == 33554432, f"Invalid boot size: {len(boot_raw)}"
    boot_sha256 = hashlib.sha256(boot_raw).digest()
    
    chunk_size = 512 * 4096 # 2,097,152 bytes (512 blocks)
    total_chunks = len(boot_raw) // chunk_size
    assert total_chunks == 16, f"Expected 16 chunks, got {total_chunks}"

    print(f"[+] Compressing {total_chunks} chunks (512 blocks / 2MB each)...")
    manifest = update_metadata_pb2.DeltaArchiveManifest()
    manifest.block_size = 4096
    manifest.minor_version = 0
    manifest.max_timestamp = 1786099104

    part = manifest.partitions.add()
    part.partition_name = "boot"
    part.new_partition_info.size = len(boot_raw)
    part.new_partition_info.hash = boot_sha256

    all_blobs = bytearray()

    for idx in range(total_chunks):
        chunk_raw = boot_raw[idx * chunk_size : (idx + 1) * chunk_size]
        xz_data = lzma.compress(chunk_raw, check=lzma.CHECK_NONE)
        bz_data = bz2.compress(chunk_raw)

        if len(bz_data) < len(xz_data):
            op_type = update_metadata_pb2.InstallOperation.REPLACE_BZ
            blob = bz_data
            type_str = "REPLACE_BZ"
        else:
            op_type = update_metadata_pb2.InstallOperation.REPLACE_XZ
            blob = xz_data
            type_str = "REPLACE_XZ"

        blob_offset = len(all_blobs)
        blob_len = len(blob)
        blob_hash = hashlib.sha256(blob).digest()
        all_blobs.extend(blob)

        op = part.operations.add()
        op.type = op_type
        op.data_offset = blob_offset
        op.data_length = blob_len
        op.data_sha256_hash = blob_hash

        ext = op.dst_extents.add()
        ext.start_block = idx * 512
        ext.num_blocks = 512

        print(f"    Op {idx:2d}: {type_str:10s} offset={blob_offset:8d} len={blob_len:8d} blocks=[{idx*512}:{(idx+1)*512}]")

    total_blobs_len = len(all_blobs)
    print(f"[+] Total operation blobs size: {total_blobs_len} bytes")

    # Signatures
    dummy_sig = update_metadata_pb2.Signatures()
    s = dummy_sig.signatures.add()
    s.version = 1
    s.data = b"\x00" * 256
    dummy_sig_bytes = dummy_sig.SerializeToString()
    sig_len = len(dummy_sig_bytes)

    manifest.signatures_offset = total_blobs_len
    manifest.signatures_size = sig_len

    manifest_bytes = manifest.SerializeToString()
    manifest_len = len(manifest_bytes)

    header_bytes = b"CrAU" + struct.pack(">Q", 2) + struct.pack(">Q", manifest_len) + struct.pack(">I", sig_len)
    metadata_bytes = header_bytes + manifest_bytes
    metadata_hash = hashlib.sha256(metadata_bytes).digest()

    meta_sig_data = private_key.sign(metadata_hash, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
    meta_sig_obj = update_metadata_pb2.Signatures()
    s_meta = meta_sig_obj.signatures.add()
    s_meta.version = 1
    s_meta.data = meta_sig_data
    meta_sig_bytes = meta_sig_obj.SerializeToString()
    assert len(meta_sig_bytes) == sig_len

    signed_payload_calc = hashlib.sha256()
    signed_payload_calc.update(metadata_bytes)
    signed_payload_calc.update(all_blobs)
    signed_payload_hash = signed_payload_calc.digest()

    pay_sig_data = private_key.sign(signed_payload_hash, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
    pay_sig_obj = update_metadata_pb2.Signatures()
    s_pay = pay_sig_obj.signatures.add()
    s_pay.version = 1
    s_pay.data = pay_sig_data
    pay_sig_bytes = pay_sig_obj.SerializeToString()
    assert len(pay_sig_bytes) == sig_len

    full_payload = metadata_bytes + meta_sig_bytes + all_blobs + pay_sig_bytes
    file_size = len(full_payload)
    file_hash = hashlib.sha256(full_payload).digest()

    import base64
    file_hash_str = base64.b64encode(file_hash).decode("ascii")
    meta_hash_str = base64.b64encode(metadata_hash).decode("ascii")

    with open(out_payload_path, "wb") as f:
        f.write(full_payload)

    props_content = (
        f"FILE_HASH={file_hash_str}\n"
        f"FILE_SIZE={file_size}\n"
        f"METADATA_HASH={meta_hash_str}\n"
        f"METADATA_SIZE={24 + manifest_len}\n"
    )

    with open(out_props_path, "w", encoding="utf-8") as f:
        f.write(props_content)

    print(f"[+] Payload created successfully: {out_payload_path} ({file_size} bytes)")
    print(f"[+] Properties:\n{props_content}")
    return file_size, manifest_len

def create_and_sign_ota(payload_path, props_path, out_zip_path):
    print(f"[+] Assembling unsigned OTA zip: {out_zip_path}.unsigned")
    unsigned_zip = out_zip_path + ".unsigned"

    upgrade_conf_path = r"D:\apps\emas-ota\upgrade.conf"
    with open(upgrade_conf_path, "r", encoding="utf-8", errors="ignore") as uf:
        upgrade_conf = uf.read()

    metadata = (
        "ota-required-cache=0\n"
        "ota-type=AB\n"
        "post-build=alps/IHU801P/IHU801P:10/QP1A.190711.020/785:user/test-keys\n"
        "post-build-incremental=785\n"
        "post-sdk-level=29\n"
        "post-security-patch-level=2021-09-05\n"
        "post-timestamp=1786099104\n"
        "pre-device=IHU801P\n"
    )

    with zipfile.ZipFile(unsigned_zip, "w") as z:
        z.writestr("META-INF/com/android/metadata", metadata)
        z.write(payload_path, "payload.bin", compress_type=zipfile.ZIP_STORED)
        z.write(props_path, "payload_properties.txt")
        for extra in ["GpsUpgrade.md5", "GpsUpgrade_3.1712.0a874a.cyfm", "fsl_app.md5", "fsl_app.s19"]:
            p = os.path.join(EXTRAS_DIR, extra)
            if os.path.exists(p):
                z.write(p, extra)
        z.writestr("upgrade.conf", upgrade_conf)
        z.writestr("META-INF/com/android/otacert", cert_bytes)

    print(f"[+] Unsigned zip created ({os.path.getsize(unsigned_zip)} bytes). Signing whole-file OTA...")
    sign_whole_file_ota(unsigned_zip, out_zip_path, PRIV_KEY_PATH, BASE_SIG_PATH)
    if os.path.exists(unsigned_zip):
        os.remove(unsigned_zip)

    # Verification
    verify_package(out_zip_path)

def verify_package(zip_file_path):
    print(f"[+] Verifying {zip_file_path} against UpdateParser & AOSP update_engine...")
    with zipfile.ZipFile(zip_file_path) as z:
        j = 0
        z_found = False
        j2 = 0
        props = None
        for info in z.infolist():
            if not z_found:
                j += len(info.filename.encode("utf-8")) + 30
                if info.extra:
                    j += len(info.extra)
            if not info.is_dir():
                if info.filename == "payload.bin":
                    j2 = info.compress_size
                    z_found = True
                elif info.filename == "payload_properties.txt":
                    props = z.read(info).decode("utf-8").splitlines()
                if not z_found:
                    j += info.compress_size

        assert z_found, "payload.bin not found"
        assert j2 > 0, "payload.bin size is 0"
        assert props is not None, "payload_properties.txt not found"

        with open(zip_file_path, "rb") as f:
            f.seek(j)
            header_magic = f.read(4)
            assert header_magic == b"CrAU", f"Offset {j} does not point to CrAU! Found: {header_magic}"

        print(f"    -> UpdateParser check: offset={j}, size={j2}, magic=CrAU (PASSED)")

        payload_data = z.read("payload.bin")
        major_v, = struct.unpack_from(">Q", payload_data, 4)
        mani_size, = struct.unpack_from(">Q", payload_data, 12)
        meta_sig_size, = struct.unpack_from(">I", payload_data, 20)

        meta_size = 24 + mani_size
        meta_hash = hashlib.sha256(payload_data[:meta_size]).digest()

        meta_sig_obj = update_metadata_pb2.Signatures()
        meta_sig_obj.ParseFromString(payload_data[meta_size : meta_size + meta_sig_size])

        pub_key = private_key.public_key()
        c_meta = int.from_bytes(meta_sig_obj.signatures[0].data, "big")
        m_meta = pow(c_meta, pub_key.public_numbers().e, pub_key.public_numbers().n).to_bytes(256, "big")
        assert m_meta.endswith(meta_hash), "C++ OpenSSL RSA decrypt does not match meta_hash!"
        print("    -> Metadata RSA signature: VERIFIED")

        mani = update_metadata_pb2.DeltaArchiveManifest()
        mani.ParseFromString(payload_data[24 : 24 + mani_size])
        assert mani.max_timestamp >= 1786099104, f"max_timestamp too small: {mani.max_timestamp}"
        assert len(mani.partitions) == 1, f"Expected 1 partition, got {len(mani.partitions)}"
        assert mani.partitions[0].partition_name == "boot", f"Expected boot, got {mani.partitions[0].partition_name}"
        assert len(mani.partitions[0].operations) == 16, f"Expected 16 ops, got {len(mani.partitions[0].operations)}"
        print(f"    -> Manifest: timestamp={mani.max_timestamp}, partitions=[boot (16 operations)] (VERIFIED)")

        sig_offset = meta_size + meta_sig_size + mani.signatures_offset
        aosp_calc = hashlib.sha256()
        aosp_calc.update(payload_data[:meta_size])
        aosp_calc.update(payload_data[meta_size + meta_sig_size : sig_offset])

        pay_sig_obj = update_metadata_pb2.Signatures()
        pay_sig_obj.ParseFromString(payload_data[sig_offset : sig_offset + mani.signatures_size])
        c_pay = int.from_bytes(pay_sig_obj.signatures[0].data, "big")
        m_pay = pow(c_pay, pub_key.public_numbers().e, pub_key.public_numbers().n).to_bytes(256, "big")
        assert m_pay.endswith(aosp_calc.digest()), "C++ OpenSSL RSA decrypt does not match payload hash!"
        print("    -> Payload RSA signature: VERIFIED")

    # Verify whole-file zip signature via ota_signer
    verify_ota_file(zip_file_path)

def build_sar_candidate(boot_path):
    """Isolated experimental output; never copy to USB or replace update.zip."""
    out_dir = os.path.join(OUTPUT_DIR, "sar_candidate")
    os.makedirs(out_dir, exist_ok=True)
    payload = os.path.join(out_dir, "payload.bin")
    props = os.path.join(out_dir, "payload_properties.txt")
    out_zip = os.path.join(OUTPUT_DIR, "update_sar_candidate.zip")
    if os.path.exists(out_zip):
        raise FileExistsError(out_zip)
    build_chunked_payload(boot_path, payload, props)
    create_and_sign_ota(payload, props, out_zip)
    return out_zip


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    out_payload = os.path.join(OUTPUT_DIR, "payload.bin")
    out_props = os.path.join(OUTPUT_DIR, "payload_properties.txt")
    out_zip = os.path.join(OUTPUT_DIR, "update.zip")

    print("=== BUILDING NEW CHUNKED OTA (16x 2MB OPERATIONS) ===")
    build_chunked_payload(PATCHED_BOOT, out_payload, out_props)
    create_and_sign_ota(out_payload, out_props, out_zip)

    # Copy to USB pendrive E:\
    if os.path.exists(USB_DIR):
        target_usb_zip = os.path.join(USB_DIR, "update.zip")
        print(f"[+] Copying {out_zip} to USB drive: {target_usb_zip}...")
        shutil.copyfile(out_zip, target_usb_zip)
        print(f"[+] Successfully written to USB: {target_usb_zip} ({os.path.getsize(target_usb_zip)} bytes)")
    else:
        print(f"[!] Warning: USB drive {USB_DIR} not found, skipping copy.")

    print("\n>>> ALL BUILD STEPS COMPLETED AND VERIFIED SUCCESSFULLY! <<<")

if __name__ == "__main__":
    main()
