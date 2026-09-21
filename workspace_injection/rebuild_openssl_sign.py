"""Rebuild update.zip from existing payload.bin, omit lying care_map, OpenSSL CMS -noattr sign."""
import os, struct, subprocess, zipfile, hashlib

ROOT = r"D:\apps\emas-ota\workspace_injection"
TOOLS = os.path.join(ROOT, "tools")
OUT = os.path.join(ROOT, "output")
EXTRAS = os.path.join(ROOT, "ota_extras")
PAYLOAD = os.path.join(OUT, "payload.bin")
PROPS = os.path.join(OUT, "payload_properties.txt")
CERT = os.path.join(TOOLS, "testkey.x509.pem")
KEY = os.path.join(TOOLS, "testkey.key.pem")
UNSIGNED = os.path.join(OUT, "update.unsigned.zip")
SIGNED = os.path.join(OUT, "update.zip")
CONTENT = os.path.join(OUT, "_tosign.bin")
PKCS7 = os.path.join(OUT, "_pkcs7.der")


OPENSSL = r"C:\Program Files\Git\usr\bin\openssl.exe"
if not os.path.exists(OPENSSL):
    OPENSSL = "openssl"


def build_unsigned():
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
    cert = open(CERT, "rb").read()
    upgrade_conf = open(r"D:\apps\emas-ota\upgrade.conf", "r", encoding="utf-8", errors="ignore").read()
    with zipfile.ZipFile(UNSIGNED, "w") as z:
        z.writestr("META-INF/com/android/metadata", metadata)
        z.write(PAYLOAD, "payload.bin", compress_type=zipfile.ZIP_STORED)
        z.write(PROPS, "payload_properties.txt")
        for extra in ["GpsUpgrade.md5", "GpsUpgrade_3.1712.0a874a.cyfm", "fsl_app.md5", "fsl_app.s19"]:
            p = os.path.join(EXTRAS, extra)
            if os.path.exists(p):
                z.write(p, extra)
        z.writestr("upgrade.conf", upgrade_conf)
        z.writestr("META-INF/com/android/otacert", cert)
    print("unsigned", os.path.getsize(UNSIGNED))


def sign():
    data = open(UNSIGNED, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    assert eocd != -1
    pre = data[: eocd + 20]
    open(CONTENT, "wb").write(pre)
    r = subprocess.run(
        [
            OPENSSL, "cms", "-sign", "-binary", "-md", "sha1", "-noattr", "-nosmimecap",
            "-signer", CERT, "-inkey", KEY,
            "-in", CONTENT, "-outform", "DER", "-out", PKCS7,
        ],
        capture_output=True, text=True,
    )
    print("openssl sign rc", r.returncode, r.stderr.strip()[-200:] if r.stderr else "")
    if r.returncode != 0:
        raise SystemExit("openssl sign failed")
    pkcs7 = open(PKCS7, "rb").read()
    print("pkcs7", len(pkcs7))
    sig_start = len(pkcs7) + 6
    comment_len = 18 + len(pkcs7) + 6
    footer = struct.pack("<HHH", sig_start, 0xFFFF, comment_len)
    comment = b"signed by SignApk\x00" + pkcs7 + footer
    assert len(comment) == comment_len
    signed = pre + struct.pack("<H", comment_len) + comment
    open(SIGNED, "wb").write(signed)
    print("signed", len(signed), SIGNED)
    v = subprocess.run(
        [
            OPENSSL, "cms", "-verify", "-binary", "-inform", "DER", "-in", PKCS7,
            "-content", CONTENT, "-CAfile", CERT, "-purpose", "any", "-noout",
        ],
        capture_output=True, text=True,
    )
    print("openssl verify rc", v.returncode)
    print((v.stderr or v.stdout)[-300:])
    if v.returncode != 0:
        raise SystemExit("openssl verify failed")
    print("OPENSSL_CMS_VERIFY_OK")

    # Copy to E:\
    if os.path.isdir("E:\\"):
        target = r"E:\update.zip"
        print(f"[+] Deploying to {target}...")
        import shutil
        shutil.copyfile(SIGNED, target)
        print(f"[+] Copied to {target} ({os.path.getsize(target)} bytes).")


if __name__ == "__main__":
    build_unsigned()
    sign()
