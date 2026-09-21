import os, sys
os.environ["PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"] = "python"

sys.path.insert(0, r"D:\apps\emas-ota\workspace_injection")
sys.path.insert(0, r"D:\apps\emas-ota\workspace_injection\.venv\Lib\site-packages\payload_dumper")

import struct, hashlib, zipfile, io, base64
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, utils
import update_metadata_pb2

ROOT_DIR = r"D:\apps\emas-ota\workspace_injection"
SYS_BUILD_DIR = os.path.join(ROOT_DIR, "sys_build")
TOOLS_DIR = os.path.join(ROOT_DIR, "tools")
EXTRAS_DIR = os.path.join(ROOT_DIR, "ota_extras")
OUTPUT_DIR = os.path.join(ROOT_DIR, "output")
OUT_PAYLOAD = os.path.join(OUTPUT_DIR, "payload.bin")
OUT_PROPS = os.path.join(OUTPUT_DIR, "payload_properties.txt")
OUT_ZIP = os.path.join(OUTPUT_DIR, "update.zip")

BOOT_PATH = os.path.join(ROOT_DIR, r"original_backup\boot.img")
SYSTEM_PATH = os.path.join(SYS_BUILD_DIR, "system.img")
PRIV_KEY_PATH = os.path.join(TOOLS_DIR, "testkey.pk8")
CERT_PATH = os.path.join(TOOLS_DIR, "testkey.x509.pem")
BASE_SIG_PATH = os.path.join(ROOT_DIR, "clean_sig.der")

os.makedirs(OUTPUT_DIR, exist_ok=True)

with open(PRIV_KEY_PATH, "rb") as f:
    private_key = serialization.load_der_private_key(f.read(), password=None)

with open(CERT_PATH, "rb") as f:
    cert_bytes = f.read()

def build_payload():
    print("[1/5] Analyzing partitions for OTA payload...")
    manifest = update_metadata_pb2.DeltaArchiveManifest()
    manifest.block_size = 4096
    manifest.minor_version = 0
    manifest.max_timestamp = 1786099104

    CHUNK_SIZE = 64 * 1024 * 1024 # 64MB chunks for smooth progress
    BLOCK_SIZE = 4096
    
    partitions = [
        ("boot", BOOT_PATH),
        ("system", SYSTEM_PATH)
    ]
    
    current_blob_offset = 0

    for part_name, img_path in partitions:
        sz = os.path.getsize(img_path)
        print(f"  Partition '{part_name}': {sz} bytes ({sz/(1024*1024):.1f} MB)")
        
        part_hash_ctx = hashlib.sha256()
        
        part = manifest.partitions.add()
        part.partition_name = part_name
        part.new_partition_info.size = sz
        
        start_block = 0
        
        with open(img_path, "rb") as f:
            while True:
                chunk = f.read(CHUNK_SIZE)
                if not chunk:
                    break
                part_hash_ctx.update(chunk)
                chunk_len = len(chunk)
                chunk_hash = hashlib.sha256(chunk).digest()
                
                num_blocks = (chunk_len + BLOCK_SIZE - 1) // BLOCK_SIZE
                
                op = part.operations.add()
                op.type = update_metadata_pb2.InstallOperation.REPLACE
                op.data_offset = current_blob_offset
                op.data_length = chunk_len
                op.data_sha256_hash = chunk_hash
                
                ext = op.dst_extents.add()
                ext.start_block = start_block
                ext.num_blocks = num_blocks
                
                current_blob_offset += chunk_len
                start_block += num_blocks

        part_hash = part_hash_ctx.digest()
        part.new_partition_info.hash = part_hash
        print(f"    Total ops: {len(part.operations)}, SHA256: {part_hash.hex()[:16]}...")

    total_blobs_len = current_blob_offset
    print(f"  Total blobs size: {total_blobs_len} bytes")

    # Dynamic Partition Metadata for Android 10 super partition
    dpm = manifest.dynamic_partition_metadata
    group = dpm.groups.add()
    group.name = "main"
    group.size = 6548914176
    group.partition_names.append("system")
    print(f"  Added dynamic_partition_metadata: group 'main', size={group.size}, dynamic partitions={list(group.partition_names)}")

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

    print("[2/5] Signing metadata and payload...")
    meta_sig_data = private_key.sign(metadata_hash, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
    meta_sig_obj = update_metadata_pb2.Signatures()
    s_meta = meta_sig_obj.signatures.add()
    s_meta.version = 1
    s_meta.data = meta_sig_data
    meta_sig_bytes = meta_sig_obj.SerializeToString()
    assert len(meta_sig_bytes) == sig_len

    signed_payload_calc = hashlib.sha256()
    signed_payload_calc.update(metadata_bytes)
    for part_name, img_path in partitions:
        with open(img_path, "rb") as f:
            while chunk := f.read(4*1024*1024):
                signed_payload_calc.update(chunk)

    signed_payload_hash = signed_payload_calc.digest()
    pay_sig_data = private_key.sign(signed_payload_hash, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
    pay_sig_obj = update_metadata_pb2.Signatures()
    s_pay = pay_sig_obj.signatures.add()
    s_pay.version = 1
    s_pay.data = pay_sig_data
    pay_sig_bytes = pay_sig_obj.SerializeToString()
    assert len(pay_sig_bytes) == sig_len

    total_payload_size = len(metadata_bytes) + len(meta_sig_bytes) + total_blobs_len + len(pay_sig_bytes)
    
    print("[3/5] Computing final payload SHA-256...")
    final_hash_ctx = hashlib.sha256()
    final_hash_ctx.update(metadata_bytes)
    final_hash_ctx.update(meta_sig_bytes)
    for part_name, img_path in partitions:
        with open(img_path, "rb") as f:
            while chunk := f.read(4*1024*1024):
                final_hash_ctx.update(chunk)
    final_hash_ctx.update(pay_sig_bytes)
    final_hash = final_hash_ctx.digest()

    file_hash_str = base64.b64encode(final_hash).decode("ascii")
    meta_hash_str = base64.b64encode(metadata_hash).decode("ascii")

    props_content = (
        f"FILE_HASH={file_hash_str}\n"
        f"FILE_SIZE={total_payload_size}\n"
        f"METADATA_HASH={meta_hash_str}\n"
        f"METADATA_SIZE={24 + manifest_len}\n"
    )

    with open(OUT_PROPS, "w", encoding="utf-8") as f:
        f.write(props_content)

    print(f"  Payload size: {total_payload_size} bytes")
    print(f"  FILE_HASH: {file_hash_str}")
    print(f"  METADATA_HASH: {meta_hash_str}")

    print("[4/5] Writing payload into unsigned OTA zip...")
    unsigned_zip = OUT_ZIP + ".unsigned"

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

    with zipfile.ZipFile(unsigned_zip, "w", zipfile.ZIP_STORED, allowZip64=True) as z:
        z.writestr("META-INF/com/android/metadata", metadata)
        z.writestr("payload_properties.txt", props_content)
        for extra in ["GpsUpgrade.md5", "GpsUpgrade_3.1712.0a874a.cyfm", "fsl_app.md5", "fsl_app.s19"]:
            p = os.path.join(EXTRAS_DIR, extra)
            if os.path.exists(p):
                z.write(p, extra)
        z.writestr("upgrade.conf", upgrade_conf)
        z.writestr("META-INF/com/android/otacert", cert_bytes)

        print("  Streaming payload.bin into zip...")
        with z.open("payload.bin", "w", force_zip64=True) as out_p:
            out_p.write(metadata_bytes)
            out_p.write(meta_sig_bytes)
            for part_name, img_path in partitions:
                print(f"    Streaming {part_name}...")
                with open(img_path, "rb") as in_f:
                    while chunk := in_f.read(4*1024*1024):
                        out_p.write(chunk)
            out_p.write(pay_sig_bytes)

    print(f"  Unsigned zip size: {os.path.getsize(unsigned_zip)} bytes")

    print("[5/5] Signing whole-file OTA with stream_sign_ota...")
    from stream_sign import stream_sign_ota
    stream_sign_ota(unsigned_zip, OUT_ZIP, PRIV_KEY_PATH, BASE_SIG_PATH)
    
    if os.path.exists(unsigned_zip):
        os.remove(unsigned_zip)

    final_zip_sz = os.path.getsize(OUT_ZIP)
    print(f"SUCCESS! Signed OTA created: {OUT_ZIP} ({final_zip_sz} bytes, {final_zip_sz/(1024*1024*1024):.2f} GiB)")
    assert final_zip_sz < 4294967295, f"ERROR: Zip size exceeds FAT32 4GB limit! {final_zip_sz}"
    print("FAT32 4GB verification passed! Package is ready for USB!")

if __name__ == "__main__":
    build_payload()
