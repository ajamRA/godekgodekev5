import sys, os, hashlib, subprocess
sys.path.insert(0, '.')
import avbtool

t = avbtool.Avb()
F = 'D:/apps/emas-ota/parts/vbmeta.img'
image = avbtool.ImageHandler(F, read_only=True)
footer, header, descriptors, image_size = t._parse_image(image)

print('== vbmeta.img descriptors ==')
for d in descriptors:
    print('  %s' % type(d).__name__)
    for attr in ('partition_name', 'rollback_index_location', 'flags',
                 'public_key_blob', 'image_size', 'salt', 'digest'):
        if hasattr(d, attr):
            v = getattr(d, attr)
            if isinstance(v, (bytes, bytearray)):
                v = v.hex()
            print('     %-24s %s' % (attr, v))
    print()

print('== candidate keys -> AVB pubkey sha1 ==')
print('   want: boot=9d808b0995768d0677fccb1efcddb7cf9e153d99')
print('         vsys=fa41159a5d696abdef93176a07d0b0d001263f01')
print('         vven=9577bc6c0772975ecce93c4d8a178662c728dadf')
print()
for f in ('tools/testkey.x509.pem', 'tools/otacert.pem', 'tools/testkey.key.pem'):
    if not os.path.exists(f):
        continue
    out = subprocess.run(['openssl', 'x509', '-in', f, '-pubkey', '-outform', 'DER'],
                         capture_output=True)
    der = out.stdout
    if not der:
        print('  %-26s (not a cert, skip)' % f)
        continue
    avbpub = len(der).to_bytes(4, 'big') + der
    print('  %-26s avb_pubkey_sha1 = %s' % (f, hashlib.sha1(avbpub).hexdigest()))
