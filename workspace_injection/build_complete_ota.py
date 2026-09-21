import os, sys, struct, hashlib, lzma, zipfile, io, shutil
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, utils
from cryptography.hazmat.primitives import serialization
from cryptography import x509
from payload_dumper import update_metadata_pb2
from ota_signer import sign_whole_file_ota, verify_ota_file

PATCHED_BOOT = r'D:\apps\emas-ota\workspace_injection\patched_build\boot.img'
ORIGINAL_BOOT = r'D:\apps\emas-ota\workspace_injection\original_backup\boot.img'
TOOLS_DIR = r'D:\apps\emas-ota\workspace_injection\tools'
EXTRAS_DIR = r'D:\apps\emas-ota\workspace_injection\ota_extras'
OUTPUT_DIR = r'D:\apps\emas-ota\workspace_injection\output'
REVERT_DIR = r'D:\apps\emas-ota\workspace_injection\revert_package'

PRIV_KEY_PATH = os.path.join(TOOLS_DIR, 'testkey.pk8')
CERT_PATH = os.path.join(TOOLS_DIR, 'testkey.x509.pem')
BASE_SIG_PATH = r'D:\apps\emas-ota\workspace_injection\clean_sig.der'

with open(PRIV_KEY_PATH, 'rb') as f:
    private_key = serialization.load_der_private_key(f.read(), password=None)

with open(CERT_PATH, 'rb') as f:
    cert_bytes = f.read()

def build_payload(boot_img_path, out_payload_path, out_props_path):
    print('Building payload for: ' + boot_img_path)
    with open(boot_img_path, 'rb') as f:
        boot_raw = f.read()
    
    assert len(boot_raw) == 33554432, 'Invalid boot size: ' + str(len(boot_raw))
    boot_sha256 = hashlib.sha256(boot_raw).digest()
    num_blocks = len(boot_raw) // 4096
    
    print('Compressing partition with XZ...')
    compressed_boot = lzma.compress(boot_raw)
    comp_size = len(compressed_boot)
    comp_sha256 = hashlib.sha256(compressed_boot).digest()
    print('Compressed size: ' + str(comp_size) + ' bytes (raw ' + str(len(boot_raw)) + ')')
    
    # Manifest Protobuf
    manifest = update_metadata_pb2.DeltaArchiveManifest()
    manifest.block_size = 4096
    manifest.minor_version = 0
    manifest.max_timestamp = 2000000000
    
    part = manifest.partitions.add()
    part.partition_name = 'boot'
    part.new_partition_info.size = len(boot_raw)
    part.new_partition_info.hash = boot_sha256
    
    op = part.operations.add()
    op.type = update_metadata_pb2.InstallOperation.REPLACE_XZ
    op.data_offset = 0
    op.data_length = comp_size
    op.data_sha256_hash = comp_sha256
    
    ext = op.dst_extents.add()
    ext.start_block = 0
    ext.num_blocks = num_blocks
    
    dummy_sig = update_metadata_pb2.Signatures()
    s = dummy_sig.signatures.add()
    s.version = 1
    s.data = b'\x00' * 256
    dummy_sig_bytes = dummy_sig.SerializeToString()
    sig_len = len(dummy_sig_bytes)
    
    manifest.signatures_offset = comp_size
    manifest.signatures_size = sig_len
    
    manifest_bytes = manifest.SerializeToString()
    manifest_len = len(manifest_bytes)
    
    # Header: Magic(4) + Version(8) + Manifest_len(8) + meta_sig_len(4) = 24 bytes
    header_bytes = b'CrAU' + struct.pack('>Q', 2) + struct.pack('>Q', manifest_len) + struct.pack('>I', sig_len)
    metadata_bytes = header_bytes + manifest_bytes
    metadata_hash = hashlib.sha256(metadata_bytes).digest()
    
    meta_sig_data = private_key.sign(metadata_hash, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
    meta_sig_obj = update_metadata_pb2.Signatures()
    s_meta = meta_sig_obj.signatures.add()
    s_meta.version = 1
    s_meta.data = meta_sig_data
    meta_sig_bytes = meta_sig_obj.SerializeToString()
    assert len(meta_sig_bytes) == sig_len
    
    # AOSP update_engine specification (CalculateHashFromPayload / signed_hash_calculator_):
    # The payload signature signs metadata_bytes + operation blobs, strictly skipping
    # the metadata signature and the payload signature itself.
    signed_payload_calc = hashlib.sha256()
    signed_payload_calc.update(metadata_bytes)
    signed_payload_calc.update(compressed_boot)
    signed_payload_hash = signed_payload_calc.digest()
    
    pay_sig_data = private_key.sign(signed_payload_hash, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
    pay_sig_obj = update_metadata_pb2.Signatures()
    s_pay = pay_sig_obj.signatures.add()
    s_pay.version = 1
    s_pay.data = pay_sig_data
    pay_sig_bytes = pay_sig_obj.SerializeToString()
    assert len(pay_sig_bytes) == sig_len
    
    full_payload = metadata_bytes + meta_sig_bytes + compressed_boot + pay_sig_bytes
    file_size = len(full_payload)
    file_hash_b64 = hashlib.sha256(full_payload).digest()
    
    import base64
    file_hash_str = base64.b64encode(file_hash_b64).decode('ascii')
    meta_hash_str = base64.b64encode(metadata_hash).decode('ascii')
    
    with open(out_payload_path, 'wb') as f:
        f.write(full_payload)
    
    # Android update_engine requires METADATA_SIZE = 24 + manifest_len
    props_content = (
        'FILE_HASH=' + file_hash_str + '\n'
        'FILE_SIZE=' + str(file_size) + '\n'
        'METADATA_HASH=' + meta_hash_str + '\n'
        'METADATA_SIZE=' + str(24 + manifest_len) + '\n'
    )
    
    with open(out_props_path, 'w', encoding='utf-8') as f:
        f.write(props_content)
    
    print('Payload created: ' + out_payload_path + ' (' + str(file_size) + ' bytes)')
    print('Properties:\n' + props_content)
    return file_size, manifest_len

def create_and_sign_ota(payload_path, props_path, out_zip_path):
    print('Creating update package: ' + out_zip_path)
    unsigned_zip = out_zip_path + '.unsigned'
    
    with open(r'D:\apps\emas-ota\upgrade.conf', 'r', encoding='utf-8', errors='ignore') as uf:
        upgrade_conf = uf.read()
    
    metadata = (
        'ota-required-cache=0\n'
        'ota-type=AB\n'
        'post-build=alps/IHU801P/IHU801P:10/QP1A.190711.020/785:user/test-keys\n'
        'post-build-incremental=785\n'
        'post-sdk-level=29\n'
        'post-security-patch-level=2021-09-05\n'
        'post-timestamp=1786099104\n'
        'pre-device=IHU801P\n'
    )
    
    with zipfile.ZipFile(unsigned_zip, 'w') as z:
        z.writestr('META-INF/com/android/metadata', metadata)
        
        for extra_file in ['care_map.pb', 'compatibility.zip']:
            p = os.path.join(EXTRAS_DIR, extra_file)
            if os.path.exists(p):
                z.write(p, extra_file)
                
        # payload.bin MUST be ZIP_STORED (uncompressed)
        z.write(payload_path, 'payload.bin', compress_type=zipfile.ZIP_STORED)
        
        with open(props_path, 'r', encoding='utf-8') as f:
            z.writestr('payload_properties.txt', f.read())
            
        for extra_file in ['GpsUpgrade.md5', 'GpsUpgrade_3.1712.0a874a.cyfm', 'fsl_app.md5', 'fsl_app.s19']:
            p = os.path.join(EXTRAS_DIR, extra_file)
            if os.path.exists(p):
                z.write(p, extra_file)
                
        z.writestr('upgrade.conf', upgrade_conf)
        z.writestr('META-INF/com/android/otacert', cert_bytes)
        
    print('Unsigned ZIP built. Signing with whole-file OTA signature...')
    sign_whole_file_ota(unsigned_zip, out_zip_path, PRIV_KEY_PATH, BASE_SIG_PATH)
    if os.path.exists(unsigned_zip):
        os.remove(unsigned_zip)
        
    verify_update_parser(out_zip_path)

def verify_update_parser(zip_file_path):
    print('Testing UpdateParser.java and AOSP update_engine compatibility on ' + zip_file_path + '...')
    with zipfile.ZipFile(zip_file_path) as z:
        j = 0
        z_found = False
        j2 = 0
        props = None
        for info in z.infolist():
            if not z_found:
                j += len(info.filename.encode('utf-8')) + 30
                if info.extra:
                    j += len(info.extra)
            if not info.is_dir():
                if info.filename == 'payload.bin':
                    j2 = info.compress_size
                    z_found = True
                elif info.filename == 'payload_properties.txt':
                    props = z.read(info).decode('utf-8').splitlines()
                if not z_found:
                    j += info.compress_size
                    
        assert z_found, 'payload.bin not found'
        assert j2 > 0, 'payload.bin size is 0'
        assert props is not None, 'payload_properties.txt not found'
        
        with open(zip_file_path, 'rb') as f:
            f.seek(j)
            header_magic = f.read(4)
            assert header_magic == b'CrAU', 'Offset j does not point to CrAU! Found: ' + str(header_magic)
            
        print('>>> UpdateParser checks PASSED! payload offset: ' + str(j) + ', size: ' + str(j2) + ', magic: CrAU <<<')
        
        # Rigorous AOSP update_engine verification
        payload_data = z.read('payload.bin')
        major_v, = struct.unpack_from('>Q', payload_data, 4)
        mani_size, = struct.unpack_from('>Q', payload_data, 12)
        meta_sig_size, = struct.unpack_from('>I', payload_data, 20)
        
        meta_size = 24 + mani_size
        meta_hash = hashlib.sha256(payload_data[:meta_size]).digest()
        
        meta_sig_obj = update_metadata_pb2.Signatures()
        meta_sig_obj.ParseFromString(payload_data[meta_size : meta_size + meta_sig_size])
        
        pub_key = private_key.public_key()
        pub_key.verify(meta_sig_obj.signatures[0].data, meta_hash, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
        
        # Raw C++ OpenSSL RSA decrypt simulation (PayloadVerifier::GetRawHashFromSignature)
        c_meta = int.from_bytes(meta_sig_obj.signatures[0].data, 'big')
        m_meta = pow(c_meta, pub_key.public_numbers().e, pub_key.public_numbers().n).to_bytes(256, 'big')
        assert m_meta.endswith(meta_hash), "C++ OpenSSL RSA decrypt does not match meta_hash!"
        print('>>> AOSP Metadata Signature (and C++ OpenSSL raw decrypt): VERIFIED! <<<')
        
        mani = update_metadata_pb2.DeltaArchiveManifest()
        mani.ParseFromString(payload_data[24 : 24 + mani_size])
        assert mani.max_timestamp >= 1786099104, 'max_timestamp too small: ' + str(mani.max_timestamp)
        print('>>> AOSP Manifest max_timestamp: ' + str(mani.max_timestamp) + ' (VERIFIED!) <<<')
        
        sig_offset = meta_size + meta_sig_size + mani.signatures_offset
        aosp_calc = hashlib.sha256()
        aosp_calc.update(payload_data[:meta_size])
        aosp_calc.update(payload_data[meta_size + meta_sig_size : sig_offset])
        
        pay_sig_obj = update_metadata_pb2.Signatures()
        pay_sig_obj.ParseFromString(payload_data[sig_offset : sig_offset + mani.signatures_size])
        pub_key.verify(pay_sig_obj.signatures[0].data, aosp_calc.digest(), padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
        
        c_pay = int.from_bytes(pay_sig_obj.signatures[0].data, 'big')
        m_pay = pow(c_pay, pub_key.public_numbers().e, pub_key.public_numbers().n).to_bytes(256, 'big')
        assert m_pay.endswith(aosp_calc.digest()), "C++ OpenSSL RSA decrypt does not match payload hash!"
        print('>>> AOSP Payload Signature (and C++ OpenSSL raw decrypt): VERIFIED! <<<')
        
        # Check whole-file OTA signature
        verify_ota_file(zip_file_path)

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    os.makedirs(REVERT_DIR, exist_ok=True)
    
    print('=== BUILDING PATCHED OTA PACKAGE ===')
    patched_payload = os.path.join(OUTPUT_DIR, 'payload.bin')
    patched_props = os.path.join(OUTPUT_DIR, 'payload_properties.txt')
    patched_zip = os.path.join(OUTPUT_DIR, 'update.zip')
    build_payload(PATCHED_BOOT, patched_payload, patched_props)
    create_and_sign_ota(patched_payload, patched_props, patched_zip)
    
    print('\n=== BUILDING FACTORY REVERT OTA PACKAGE ===')
    revert_payload = os.path.join(REVERT_DIR, 'payload.bin')
    revert_props = os.path.join(REVERT_DIR, 'payload_properties.txt')
    revert_zip = os.path.join(REVERT_DIR, 'update_revert.zip')
    build_payload(ORIGINAL_BOOT, revert_payload, revert_props)
    create_and_sign_ota(revert_payload, revert_props, revert_zip)
    
    print('\n>>> ALL PACKAGES BUILT AND VERIFIED SUCCESSFULLY! <<<')

if __name__ == '__main__':
    main()
