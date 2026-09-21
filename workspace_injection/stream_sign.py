import os, struct, hashlib
from cryptography.hazmat.primitives.serialization import load_der_private_key
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.utils import Prehashed
import shutil

def stream_sign_ota(in_path, out_path, privkey_path, base_sig_der_path):
    print("Hashing in chunks...")
    with open(in_path, "rb") as f:
        f.seek(0, 2)
        total_size = f.tell()
        
        # Find EOCD
        f.seek(max(0, total_size - 65536))
        tail = f.read()
        eocd_pos = tail.rfind(b"PK\x05\x06")
        if eocd_pos == -1: raise ValueError("EOCD not found")
        
        eocd_abs_pos = total_size - len(tail) + eocd_pos
        
        # Check if Zip64 locator exists right before 32-bit EOCD
        is_zip64 = False
        if eocd_abs_pos >= 20:
            f.seek(eocd_abs_pos - 20)
            loc64 = f.read(20)
            if loc64.startswith(b"PK\x06\x07"):
                is_zip64 = True
                print("  Zip64 archive detected! Enforcing 32-bit EOCD cenoff = 0xFFFFFFFF for Android 10 compatibility.")
        
        # Read 32-bit EOCD
        f.seek(eocd_abs_pos)
        eocd_20 = bytearray(f.read(20))
        if is_zip64:
            eocd_20[16:20] = b"\xff\xff\xff\xff"
        
        # Hash stream up to eocd_abs_pos, then append eocd_20
        h = hashlib.sha1()
        f.seek(0)
        remaining = eocd_abs_pos
        while remaining > 0:
            chunk = f.read(min(remaining, 4*1024*1024))
            if not chunk: break
            h.update(chunk)
            remaining -= len(chunk)
            
        h.update(eocd_20)
        sha1 = h.digest()
        
    print(f"SHA-1 digest: {sha1.hex()}")
    
    with open(privkey_path, "rb") as kf:
        privkey = load_der_private_key(kf.read(), password=None)
        
    rsa_sig = privkey.sign(sha1, padding.PKCS1v15(), Prehashed(hashes.SHA1()))
    
    with open(base_sig_der_path, "rb") as bsf:
        clean_sig_template = bsf.read()
        
    pkcs7_der = clean_sig_template[:-256] + rsa_sig
    
    comment_len = 1738
    sig_start = 1720
    footer = struct.pack('<HHH', sig_start, 0xffff, comment_len)
    comment = b'signed by SignApk\x00' + pkcs7_der + footer
    
    eocd_comment_len_bytes = struct.pack('<H', comment_len)
    
    print("Writing signed zip...")
    with open(in_path, "rb") as in_f, open(out_path, "wb") as out_f:
        remaining = eocd_abs_pos
        while remaining > 0:
            chunk = in_f.read(min(remaining, 4*1024*1024))
            if not chunk: break
            out_f.write(chunk)
            remaining -= len(chunk)
        
        out_f.write(eocd_20)
        out_f.write(eocd_comment_len_bytes)
        out_f.write(comment)

    print("Signed zip written. Running verification...")
    from ota_signer import verify_ota_file
    verify_ota_file(out_path)

ROOT_DIR = r"D:\apps\emas-ota\workspace_injection"
OUTPUT_DIR = r"D:\apps\emas-ota\workspace_injection\output"
OUT_ZIP = os.path.join(OUTPUT_DIR, "update_full.zip")
TOOLS_DIR = os.path.join(ROOT_DIR, "tools")
PRIV_KEY_PATH = os.path.join(TOOLS_DIR, "testkey.pk8")
BASE_SIG_PATH = os.path.join(ROOT_DIR, "clean_sig.der")

stream_sign_ota(OUT_ZIP + ".unsigned", OUT_ZIP, PRIV_KEY_PATH, BASE_SIG_PATH)
print("Done.")
