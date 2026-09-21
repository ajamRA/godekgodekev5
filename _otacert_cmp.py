import zipfile, hashlib, os
from cryptography import x509
from cryptography.hazmat.primitives import serialization

def avb_sha1(pem):
    c = x509.load_pem_x509_certificate(pem)
    der = c.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha1(der).hexdigest()

def grab(path):
    if not os.path.exists(path):
        return None, None
    if path.lower().endswith(".pem"):
        with open(path, "rb") as f:
            return "pem-file", f.read()
    try:
        z = zipfile.ZipFile(path)
    except Exception as e:
        return "ERR", None
    for n in z.namelist():
        if "otacert" in n.lower():
            return n, z.read(n)
    return "none", None

TARGETS = [
    ("KITA      ", r"workspace_injection/output/update.zip"),
    ("KILANG-GEELY", r"New folder/3C6025_SW0E22H0128H111100000_user_995/OS/update.zip"),
    ("ota_emas5 ", r"ota_emas5.zip"),
    ("old_output", r"workspace_injection/old_output/update.zip"),
    ("revert    ", r"workspace_injection/revert_package/update_revert.zip"),
    ("hook      ", r"workspace_injection/candidate_hook/update_hook.zip"),
    ("root-otacert", r"META-INF/com/android/otacert"),
    ("_ru_inspect", r"_ru_inspect/META-INF/com/android/otacert"),
    ("tools-pem ", r"workspace_injection/tools/otacert.pem"),
]

for label, path in TARGETS:
    n, d = grab(path)
    if d is None:
        print("%s | %-55s -> TIADA (%s)" % (label, path[:55], n))
        continue
    try:
        h = avb_sha1(d)
    except Exception as e:
        h = "ERR " + str(e)[:40]
    print("%s | %-24s | sha256=%s | avb_pubkey_sha1=%s" % (label, str(n)[:24], hashlib.sha256(d).hexdigest()[:16], h))

print()
print("rujukan: Proton chain-boot key = 9d808b0995768d0677fccb1efcddb7cf9e153d99")
print("rujukan: testkey (kita)        = 55d55d053e39ece8d88d8a546db6d022f10b3a7b")
