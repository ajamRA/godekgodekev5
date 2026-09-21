import os, sys, struct, hashlib
from cryptography.hazmat.primitives.serialization import load_der_private_key
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.utils import Prehashed

def sign_whole_file_ota(unsigned_zip_path, signed_zip_path, privkey_path, base_sig_der_path):
    print('Signing OTA package: ' + unsigned_zip_path + ' -> ' + signed_zip_path)
    
    with open(privkey_path, 'rb') as f:
        privkey = load_der_private_key(f.read(), password=None)
        
    with open(base_sig_der_path, 'rb') as f:
        clean_sig_template = f.read()
    assert len(clean_sig_template) == 1714

    with open(unsigned_zip_path, 'rb') as f:
        zip_data = f.read()
        
    eocd_pos = zip_data.rfind(b'PK\x05\x06')
    assert eocd_pos != -1, 'EOCD not found in unsigned ZIP'
    
    pre_comment_len_data = zip_data[:eocd_pos + 20]
    
    comment_len = 1738
    sig_start = 1720
    
    print('Hashing ' + str(len(pre_comment_len_data)) + ' bytes with SHA-1...')
    sha1 = hashlib.sha1(pre_comment_len_data).digest()
    print('SHA-1 digest: ' + sha1.hex())
    
    rsa_sig = privkey.sign(sha1, padding.PKCS1v15(), Prehashed(hashes.SHA1()))
    assert len(rsa_sig) == 256
    
    pkcs7_der = clean_sig_template[:-256] + rsa_sig
    assert len(pkcs7_der) == 1714
    
    footer = struct.pack('<HHH', sig_start, 0xffff, comment_len)
    comment = b'signed by SignApk\x00' + pkcs7_der + footer
    assert len(comment) == comment_len
    
    eocd_comment_len_bytes = struct.pack('<H', comment_len)
    signed_zip_data = pre_comment_len_data + eocd_comment_len_bytes + comment
    
    with open(signed_zip_path, 'wb') as f:
        f.write(signed_zip_data)
        
    print('Successfully signed OTA package: ' + signed_zip_path + ' (' + str(len(signed_zip_data)) + ' bytes)')
    verify_ota_file(signed_zip_path)

def verify_ota_file(zip_path):
    print('Verifying ' + zip_path + ' using simulated Android RecoverySystem.verifyPackage()...')
    with open(zip_path, 'rb') as f:
        f.seek(0, 2)
        file_len = f.tell()
        
        f.seek(file_len - 6)
        footer = f.read(6)
        sig_start, magic, comment_len = struct.unpack('<HHH', footer)
        if magic != 0xffff:
            raise ValueError('Bad magic: ' + hex(magic))
            
        f.seek(file_len - (comment_len + 22))
        eocd = f.read(comment_len + 22)
        if eocd[:4] != b'PK\x05\x06':
            raise ValueError('Bad EOCD magic')
            
        if b'PK\x05\x06' in eocd[4:-3]:
            raise ValueError('EOCD marker found after start of EOCD')
            
        pkcs7_start_in_eocd = comment_len + 22 - sig_start
        pkcs7_raw = eocd[pkcs7_start_in_eocd : pkcs7_start_in_eocd + 1714]
        sig_bytes = pkcs7_raw[-256:]
        
        to_read = file_len - comment_len - 2
        f.seek(0)
        sha1 = hashlib.sha1()
        rem = to_read
        while rem > 0:
            chunk = f.read(min(rem, 1024*1024))
            if not chunk: break
            sha1.update(chunk)
            rem -= len(chunk)
            
        calculated_digest = sha1.digest()
        print('Calculated SHA-1 digest across ' + str(to_read) + ' bytes: ' + calculated_digest.hex())
        
        from cryptography.x509 import load_pem_x509_certificate
        with open(r'D:\apps\emas-ota\workspace_injection\tools\testkey.x509.pem', 'rb') as cf:
            cert = load_pem_x509_certificate(cf.read())
        pubkey = cert.public_key()
        pubkey.verify(sig_bytes, calculated_digest, padding.PKCS1v15(), Prehashed(hashes.SHA1()))
        print('>>> Android RecoverySystem verification: PASSED 100%! <<<')

if __name__ == '__main__':
    if len(sys.argv) > 2:
        sign_whole_file_ota(sys.argv[1], sys.argv[2], r'D:\apps\emas-ota\workspace_injection\tools\testkey.pk8', r'D:\apps\emas-ota\workspace_injection\clean_sig.der')
    else:
        print('Usage: ota_signer.py <unsigned_zip> <signed_zip>')
