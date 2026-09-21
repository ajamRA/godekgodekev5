import os, sys
os.environ["PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"] = "python"

sys.path.insert(0, r"D:\apps\emas-ota\workspace_injection")
sys.path.insert(0, r"D:\apps\emas-ota\workspace_injection\.venv\Lib\site-packages\payload_dumper")

import struct, hashlib, lzma, io, time, base64
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, utils
import update_metadata_pb2

ROOT_DIR = r"D:\apps\emas-ota\workspace_injection"
TOOLS_DIR = os.path.join(ROOT_DIR, "tools")
OUTPUT_DIR = os.path.join(ROOT_DIR, "output")

ORIG_PAYLOAD = r"D:\apps\emas-ota\payload.bin"
ORIG_SYSTEM = r"D:\apps\emas-ota\parts\system.img"
PATCHED_SYSTEM = os.path.join(ROOT_DIR, r"sys_build\system.img")
PRIV_KEY_PATH = os.path.join(TOOLS_DIR, "testkey.pk8")
CERT_PATH = os.path.join(TOOLS_DIR, "testkey.x509.pem")

OUT_PAYLOAD = os.path.join(OUTPUT_DIR, "payload.bin")
OUT_PROPS = os.path.join(OUTPUT_DIR, "payload_properties.txt")
TEMP_BLOBS = os.path.join(OUTPUT_DIR, "_temp_blobs.bin")

os.makedirs(OUTPUT_DIR, exist_ok=True)

with open(PRIV_KEY_PATH, "rb") as f:
    private_key = serialization.load_der_private_key(f.read(), password=None)

def build_full_payload():
    print("=" * 60)
    print("[1/5] Reading original payload manifest...")
    print("=" * 60)
    
    with open(ORIG_PAYLOAD, "rb") as f:
        magic = f.read(4)
        assert magic == b"CrAU", f"Invalid magic: {magic}"
        version = struct.unpack(">Q", f.read(8))[0]
        manifest_len = struct.unpack(">Q", f.read(8))[0]
        orig_sig_len = struct.unpack(">I", f.read(4))[0]
        manifest_bytes = f.read(manifest_len)
        orig_data_offset = 4 + 8 + 8 + 4 + manifest_len + orig_sig_len

    manifest = update_metadata_pb2.DeltaArchiveManifest()
    manifest.ParseFromString(manifest_bytes)
    print(f"  Original manifest partitions: {len(manifest.partitions)}")
    print(f"  Dynamic partition groups: {len(manifest.dynamic_partition_metadata.groups)}")
    for g in manifest.dynamic_partition_metadata.groups:
        print(f"    Group '{g.name}' (size {g.size}): {list(g.partition_names)}")

    print("\n" + "=" * 60)
    print("[2/5] Identifying differing chunks in system.img...")
    print("=" * 60)
    
    CHUNK = 2 * 1024 * 1024
    differing_chunks = set()
    idx = 0
    sys_hash_ctx = hashlib.sha256()
    
    with open(ORIG_SYSTEM, "rb") as f1, open(PATCHED_SYSTEM, "rb") as f2:
        while True:
            c1 = f1.read(CHUNK)
            c2 = f2.read(CHUNK)
            if not c2:
                break
            sys_hash_ctx.update(c2)
            if c1 != c2:
                differing_chunks.add(idx)
            idx += 1
            
    patched_sys_hash = sys_hash_ctx.digest()
    patched_sys_size = os.path.getsize(PATCHED_SYSTEM)
    print(f"  Total system chunks: {idx}")
    print(f"  Differing chunks to re-compress: {len(differing_chunks)}")
    print(f"  Patched system SHA-256: {patched_sys_hash.hex()}")

    print("\n" + "=" * 60)
    print("[3/5] Rebuilding payload blobs (copying stock + injecting patched system)...")
    print("=" * 60)
    
    new_blob_offset = 0
    t0 = time.time()
    
    with open(ORIG_PAYLOAD, "rb") as f_orig_pay, \
         open(PATCHED_SYSTEM, "rb") as f_sys, \
         open(TEMP_BLOBS, "wb") as f_out_blob:
         
        for p in manifest.partitions:
            p_name = p.partition_name
            if p_name != "system":
                print(f"  Processing stock partition '{p_name}' ({len(p.operations)} ops)...")
                for op in p.operations:
                    f_orig_pay.seek(orig_data_offset + op.data_offset)
                    blob_data = f_orig_pay.read(op.data_length)
                    assert len(blob_data) == op.data_length
                    
                    op.data_offset = new_blob_offset
                    f_out_blob.write(blob_data)
                    new_blob_offset += len(blob_data)
            else:
                print(f"  Processing patched partition 'system' ({len(p.operations)} ops, {len(differing_chunks)} modified)...")
                p.new_partition_info.size = patched_sys_size
                p.new_partition_info.hash = patched_sys_hash
                
                for op_idx, op in enumerate(p.operations):
                    ext = op.dst_extents[0]
                    chunk_start = ext.start_block * 4096
                    chunk_len = ext.num_blocks * 4096
                    
                    if op_idx in differing_chunks:
                        f_sys.seek(chunk_start)
                        raw_chunk = f_sys.read(chunk_len)
                        compressed = lzma.compress(raw_chunk, format=lzma.FORMAT_XZ, check=lzma.CHECK_NONE, preset=6)
                        
                        op.type = update_metadata_pb2.InstallOperation.REPLACE_XZ
                        op.data_length = len(compressed)
                        op.data_sha256_hash = hashlib.sha256(compressed).digest()
                        op.data_offset = new_blob_offset
                        
                        f_out_blob.write(compressed)
                        new_blob_offset += len(compressed)
                    else:
                        f_orig_pay.seek(orig_data_offset + op.data_offset)
                        blob_data = f_orig_pay.read(op.data_length)
                        assert len(blob_data) == op.data_length
                        
                        op.data_offset = new_blob_offset
                        f_out_blob.write(blob_data)
                        new_blob_offset += len(blob_data)
                        
    t1 = time.time()
    print(f"  Rebuilt all blobs in {t1 - t0:.2f}s! Total blobs size: {new_blob_offset} bytes ({new_blob_offset/(1024*1024):.2f} MB)")

    print("\n" + "=" * 60)
    print("[4/5] Signing manifest and payload...")
    print("=" * 60)
    
    total_blobs_len = new_blob_offset
    
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

    print("  Calculating signed payload hash...")
    signed_payload_calc = hashlib.sha256()
    signed_payload_calc.update(metadata_bytes)
    with open(TEMP_BLOBS, "rb") as f:
        while chunk := f.read(16 * 1024 * 1024):
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

    print("\n" + "=" * 60)
    print("[5/5] Assembling final payload.bin...")
    print("=" * 60)
    
    final_hash_ctx = hashlib.sha256()
    final_hash_ctx.update(metadata_bytes)
    final_hash_ctx.update(meta_sig_bytes)
    
    with open(OUT_PAYLOAD, "wb") as f_out:
        f_out.write(metadata_bytes)
        f_out.write(meta_sig_bytes)
        with open(TEMP_BLOBS, "rb") as f_blob:
            while chunk := f_blob.read(16 * 1024 * 1024):
                final_hash_ctx.update(chunk)
                f_out.write(chunk)
        final_hash_ctx.update(pay_sig_bytes)
        f_out.write(pay_sig_bytes)

    os.remove(TEMP_BLOBS)
    
    final_payload_hash = final_hash_ctx.digest()
    final_payload_size = os.path.getsize(OUT_PAYLOAD)
    assert final_payload_size == total_payload_size

    print(f"  payload.bin generated successfully!")
    print(f"  Size: {final_payload_size} bytes ({final_payload_size/(1024*1024):.2f} MB)")
    print(f"  SHA-256: {final_payload_hash.hex()}")

    meta_hash_b64 = base64.b64encode(metadata_hash).decode()
    payload_hash_b64 = base64.b64encode(final_payload_hash).decode()

    props = (
        f"FILE_HASH={payload_hash_b64}\n"
        f"FILE_SIZE={final_payload_size}\n"
        f"METADATA_HASH={meta_hash_b64}\n"
        f"METADATA_SIZE={len(metadata_bytes)}\n"
    )
    with open(OUT_PROPS, "w") as f:
        f.write(props)
        
    print("\npayload_properties.txt:")
    print(props)
    return True

if __name__ == "__main__":
    build_full_payload()
