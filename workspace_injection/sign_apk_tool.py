import os, sys, zipfile, subprocess

JAVA_BIN = r"C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin"
KEYTOOL = os.path.join(JAVA_BIN, "keytool.exe")
JARSIGNER = os.path.join(JAVA_BIN, "jarsigner.exe")
KEYSTORE = r"D:\apps\emas-ota\workspace_injection\debug.keystore"

def ensure_keystore():
    if not os.path.exists(KEYSTORE):
        cmd = [
            KEYTOOL, "-genkey", "-v",
            "-keystore", KEYSTORE,
            "-storepass", "android",
            "-alias", "androiddebugkey",
            "-keypass", "android",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US"
        ]
        subprocess.run(cmd, check=True)

def patch_manifest(manifest_bytes):
    manifest = bytearray(manifest_bytes)
    targets = [
        b'READ_EPG_DATA',
        b'WRITE_EPG_DATA',
        b'DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
    ]
    for t in targets:
        t_u16 = t.decode('ascii').encode('utf-16le')
        rep_u16 = ('X' * len(t)).encode('utf-16le')
        if t_u16 in manifest:
            print(f'  [Patch] Replacing UTF-16 {t.decode()}...')
            manifest = bytearray(manifest.replace(t_u16, rep_u16))
        if t in manifest:
            print(f'  [Patch] Replacing ASCII {t.decode()}...')
            manifest = bytearray(manifest.replace(t, ('X' * len(t)).encode('ascii')))
    return bytes(manifest)

def process_and_sign(apk_in, apk_out):
    ensure_keystore()
    temp_apk = apk_out + ".temp.apk"
    
    print(f"Repackaging {apk_in} -> {temp_apk}...")
    with zipfile.ZipFile(apk_in, 'r') as z_in, zipfile.ZipFile(temp_apk, 'w') as z_out:
        for item in z_in.infolist():
            if item.filename.startswith('META-INF/'):
                continue
            data = z_in.read(item.filename)
            if item.filename == 'AndroidManifest.xml':
                data = patch_manifest(data)
                
            # Preserve original compression or use STORED for native libs
            compress_type = zipfile.ZIP_STORED if item.filename.startswith('lib/') else zipfile.ZIP_DEFLATED
            z_out.writestr(item.filename, data, compress_type=compress_type)
            
    print(f"Signing with jarsigner -> {apk_out}...")
    cmd = [
        JARSIGNER,
        "-keystore", KEYSTORE,
        "-storepass", "android",
        "-keypass", "android",
        "-sigalg", "SHA256withRSA",
        "-digestalg", "SHA-256",
        "-signedjar", apk_out,
        temp_apk,
        "androiddebugkey"
    ]
    subprocess.run(cmd, check=True)
    if os.path.exists(temp_apk):
        os.remove(temp_apk)
    print(f"SUCCESS: {apk_out} signed and ready ({os.path.getsize(apk_out)} bytes)")

if __name__ == '__main__':
    if len(sys.argv) > 2:
        process_and_sign(sys.argv[1], sys.argv[2])
    else:
        print("Usage: python sign_apk_tool.py <input> <output>")
