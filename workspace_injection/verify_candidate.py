"""Final end-to-end verification of candidate OTA package.

Independently:
  1. parse payload, rebuild boot.img from ops, compare to patched_build/boot.img
  2. verify every op sha256
  3. verify metadata hash / file hash against payload_properties.txt
  4. verify metadata RSA signature (sha256 over CrAU header+manifest)
  5. verify payload RSA signature (sha256 over header+manifest+metasig+blobs)
  6. verify whole-file OA1 PKCS#7 (openssl)
  7. inspect ramdisk content: props, rc files, init binary patches
"""
import bz2
import base64
import hashlib
import lzma
import struct
import subprocess
import sys
import zipfile
import gzip

Z = 'output/update.zip'
CERT = 'META-INF/com/android/otacert'


def rv(b, i):
    v = 0
    sh = 0
    while True:
        x = b[i]
        i += 1
        v |= (x & 0x7F) << sh
        sh += 7
        if not x & 0x80:
            break
    return v, i


def flds(buf):
    out = []
    i = 0
    while i < len(buf):
        tag, i = rv(buf, i)
        f = tag >> 3
        wt = tag & 7
        if wt == 0:
            v, i = rv(buf, i)
            out.append((f, v))
        elif wt == 2:
            ln, i = rv(buf, i)
            out.append((f, buf[i:i + ln]))
            i += ln
        else:
            raise ValueError('wt %d' % wt)
    return out


def sig_of(blob):
    s = [v for f, v in flds(blob) if f == 1][0]
    return [v for f, v in flds(s) if f == 2][0]


ok = True
def check(label, cond, extra=''):
    global ok
    ok = ok and bool(cond)
    print('[%s] %s %s' % ('PASS' if cond else 'FAIL', label, extra))


raw = open(Z, 'rb').read()
zf = zipfile.ZipFile(Z)
payload = zf.read('payload.bin')
props = zf.read('payload_properties.txt').decode()

print('=' * 62)
print('OTA CANDIDATE:', Z)
print('size        :', len(raw))
print('sha256      :', hashlib.sha256(raw).hexdigest())
print('=' * 62)

# --- 1. zip sane -----------------------------------------------------------
check('zip integrity (CRC of all entries)', zf.testzip() is None)

ml = int.from_bytes(payload[12:20], 'big')
ssl = int.from_bytes(payload[20:24], 'big')
body = payload[24:24 + ml]
total = [v for f, v in flds(body) if f == 4][0]
part = [v for f, v in flds(body) if f == 13][0]
pname = [v for f, v in flds(part) if f == 1][0].decode()
ops = [v for f, v in flds(part) if f == 8]
base = 24 + ml + ssl

print('\n-- payload layout --')
print('manifest_len %d  meta_sig_len %d  blobs %d' % (ml, ssl, total))
print('partition   %s   bytes = %d == file len %d' %
      (pname, 24 + ml + ssl + total + ssl, len(payload)))
check('partition is boot', pname == 'boot')
check('payload total == file size', 24 + ml + ssl + total + ssl == len(payload))

# --- 2. rebuild boot.img from operations ----------------------------------
img = bytearray()
offsets = ['%d' % d for d in (0,)]
import bz2 as _bz2
for o in ops:
    d = {f: v for f, v in flds(o)}
    blob = payload[base + d[2]:base + d[2] + d[3]]
    if d[1] == 1:
        dec = _bz2.decompress(blob)
    else:
        dec = lzma.decompress(blob, format=lzma.FORMAT_XZ)
    check('op@%-9d data_sha256 == sha256(op blob)' % d[2],
          hashlib.sha256(blob).digest() == d.get(8))
    img += dec
img = bytes(img)

disk = open('patched_build/boot.img', 'rb').read()
print('\n-- boot.img reconstruction --')
print('rebuilt  %d bytes sha256 %s' % (len(img), hashlib.sha256(img).hexdigest()))
print('on-disk  %d bytes sha256 %s' % (len(disk), hashlib.sha256(disk).hexdigest()))
check('reconstructed boot.img == patched_build/boot.img', img == disk)

# --- 3. props -------------------------------------------------------------
def prop(key):
    return [l.split('=', 1)[1] for l in props.splitlines() if l.startswith(key + '=')][0]

print('\n-- payload_properties.txt --')
check('FILE_SIZE', int(prop('FILE_SIZE')) == len(payload), '(%s)' % prop('FILE_SIZE'))
check('FILE_HASH  == sha256(payload)',
      prop('FILE_HASH') == base64.b64encode(hashlib.sha256(payload).digest()).decode())
check('METADATA_SIZE == 24+manifest_len',
      int(prop('METADATA_SIZE')) == 24 + ml, '(%s)' % prop('METADATA_SIZE'))
check('METADATA_HASH == sha256(payload[:%d])' % (24 + ml),
      prop('METADATA_HASH') == base64.b64encode(hashlib.sha256(payload[:24 + ml]).digest()).decode())

# --- 4/5. signatures ------------------------------------------------------
# NOTE: update_engine signs with utils.Prehashed(SHA256), i.e. the 32-byte
# digest is passed to PKCS#1 v1.5 as if it were the message. Python's
# cryptography.verify(Prehashed(...)) would hash it AGAIN (double hash) and
# always report InvalidSignature. So verify at the RSA-EM level, exactly as
# AOSP's payload_consumer does: RSA-decrypt with (e, n) and compare the
# PKCS#1 v1.5 DigestInfo suffix against the expected digest.
import base64 as _b64
from cryptography.hazmat.primitives import serialization

pk8 = serialization.load_der_private_key(open('tools/testkey.pk8', 'rb').read(), password=None)
_nums = pk8.public_key().public_numbers()
N, E = _nums.n, _nums.e
MOD_BYTES = (N.bit_length() + 7) // 8
print('public exponent e =', E, '(AOSP testkey uses e=3)')
SHA256_DIGESTINFO = bytes.fromhex('3031300d060960864801650304020105000420')


def rsa_pkcs1_sha256_verify(sig: bytes, digest: bytes) -> bool:
    """Return True iff sig is PKCS#1 v1.5 SHA-256 over the given digest."""
    if len(sig) != MOD_BYTES:
        return False
    em = pow(int.from_bytes(sig, 'big'), E, N).to_bytes(MOD_BYTES, 'big')
    if not em.startswith(b'\x00\x01'):
        return False
    sep = em.index(b'\x00', 2)
    if b'\xff' * (sep - 2) != em[2:sep]:
        return False
    t = em[sep + 1:]
    return t == SHA256_DIGESTINFO + digest


meta_sig = sig_of(payload[24 + ml:24 + ml + ssl])
pay_sig = sig_of(payload[24 + ml + ssl + total:])
# AOSP CalculateHashFromPayload() (payload_generator/payload_signer.cc):
#   calc.Update(payload.data(), metadata_size)          # header + manifest
#   calc.Update(payload + metadata_size + metadata_sig_size,
#               signatures_offset - metadata_size - metadata_sig_size)
# i.e. the payload signature covers header+manifest+blobs and deliberately
# SKIPS the metadata-signature block in the middle.
aosp_signed = payload[:24 + ml] + payload[24 + ml + ssl:24 + ml + ssl + total]

print('\n-- payload signatures (RSA-2048 PKCS#1 v1.5 SHA-256) --')
print('key modulus:', MOD_BYTES * 8, 'bits')
check('metadata sig = PKCS#1v1.5(SHA256(header+manifest))',
      rsa_pkcs1_sha256_verify(meta_sig, hashlib.sha256(payload[:24 + ml]).digest()))
check('payload  sig = PKCS#1v1.5(SHA256(header+manifest+blobs)) [AOSP skip-metasig]',
      rsa_pkcs1_sha256_verify(pay_sig, hashlib.sha256(aosp_signed).digest()))

# manifest signature pointer: offset must point at the payload signature block
man_sig_off = [v for f, v in flds(body) if f == 11]
man_sig_sz = [v for f, v in flds(body) if f == 12]
check('manifest.signatures_offset == total_blobs_len (%d)' % total,
      man_sig_off == [] or man_sig_off[0] == total, '(field=%s)' % man_sig_off)
# 0 is the proto3 default => field elided, and it is written into the CrAU header
# instead (payload_signature_size). Verify the header carries it.
hdr_sig_sz = int.from_bytes(payload[20:24], 'big')
check('CrAU header payload_signature_size == 264', hdr_sig_sz == 264, '(%d)' % hdr_sig_sz)

# --- 6. whole-file OA1 PKCS#7 --------------------------------------------
# AOSP writes: [2-byte LE length of signature comment][comment][DER]
# comment = b"signature\0" prefix ("signed by SignApk\0") + DER, and the length
# field is the LAST 2 bytes of that block -- i.e. at [-(len+2):-len].
sig_len = int.from_bytes(raw[-2:], 'little')
sig_region = raw[-(sig_len + 2):]
check('trailing length field == len(comment)', sig_len == len(sig_region) - 2)
comment = sig_region[2:]
der = comment[18:]
content = raw[:-(sig_len + 2)]
open('_v_sig.der', 'wb').write(der)
open('_v_content.bin', 'wb').write(content)

print('\n-- whole-file OTA signature (SignApk PKCS#7) --')
print('footer comment len :', sig_len)
print('DER length         :', len(der))
r = subprocess.run(['openssl', 'smime', '-verify', '-inform', 'DER', '-in', '_v_sig.der',
                    '-content', '_v_content.bin', '-noverify', '-out', 'NUL'],
                   capture_output=True, text=True)
check('openssl smime -verify (whole-file OTA)', r.returncode == 0,
      r.stdout.strip() or r.stderr.strip()[:80])

otacert = zf.read(CERT)
print('otacert subject    :',
      subprocess.run(['openssl', 'x509', '-inform', 'PEM', '-noout', '-subject'],
                     input=otacert.decode(), capture_output=True, text=True).stdout.strip())

# --- 7. ramdisk inspection ------------------------------------------------
ksz = struct.unpack_from('<I', img, 8)[0]
rsz = struct.unpack_from('<I', img, 16)[0]
page = struct.unpack_from('<I', img, 36)[0]
roff = page + (ksz + page - 1) // page * page
cpio = gzip.decompress(img[roff:roff + rsz])

entries = {}
i = 0
while i + 110 <= len(cpio):
    if cpio[i:i + 6] not in (b'070701', b'070702'):
        nxt = cpio.find(b'070701', i)
        if nxt == -1 or nxt - i > 8192:
            break
        i = nxt
        continue
    fs = int(cpio[i + 54:i + 62], 16)
    ns = int(cpio[i + 94:i + 102], 16)
    no = i + 110
    name = cpio[no:no + ns].split(b'\x00', 1)[0].decode('utf-8', 'replace')
    do = (no + ns + 3) // 4 * 4
    entries[name] = cpio[do:do + fs]
    i = (do + fs + 3) // 4 * 4
    if name == 'TRAILER!!!':
        break

cmdline = img[64:64 + 512].split(b'\x00')[0].decode(errors='replace')
print('\n-- ramdisk --')
print('cmdline:', cmdline)
check('cmdline buildvariant=userdebug', 'buildvariant=userdebug' in cmdline)
check('cmdline androidboot.debuggable=1', 'androidboot.debuggable=1' in cmdline)

for f in ('p', 'w', 'force_debuggable', 'debug_ramdisk/adb_debug.prop', 'prop.default'):
    check('ramdisk contains %s' % f, f in entries)

p_txt = entries.get('p', b'').decode('latin1')
for kv in ('ro.debuggable=1', 'ro.secure=0', 'ro.adb.secure=0', 'ro.build.type=userdebug'):
    check('overlay p sets %s' % kv, kv in p_txt)

w_txt = entries.get('w', b'').decode('latin1')
for kv in ('service.adb.tcp.port 5555', 'persist.adb.tcp.port 5555', 'start adbd',
           'adb_enabled 1'):
    check('overlay w contains "%s"' % kv, kv in w_txt)

init_bin = entries['system/bin/init']
check('init: /first_stage_ramdisk/p string present', b'/first_stage_ramdisk/p\x00' in init_bin)
check('init: branch at 0x6b6c8 -> 0xa9f50', init_bin[0x6b6c8:0x6b6cc] == bytes.fromhex('41441f54'))
check('init: 0x5c77c == mov w19,#1', init_bin[0x5C77C:0x5C780] == bytes.fromhex('33008052'))

print('\n' + '=' * 62)
print('OVERALL:', 'ALL CHECKS PASSED' if ok else 'FAILURES PRESENT')
print('=' * 62)
sys.exit(0 if ok else 1)
