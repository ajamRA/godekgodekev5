"""Diagnose metadata/payload signature mismatch."""
import sys
import hashlib

sys.path.insert(0, '.')
import build_chunked_ota as B
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import hashes, serialization

payload = open('_scratch/payload.bin', 'rb').read()


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
            raise ValueError
    return out


ml = int.from_bytes(payload[12:20], 'big')
ssl = int.from_bytes(payload[20:24], 'big')
man = payload[24:24 + ml]
total = [v for f, v in flds(man) if f == 4][0]
sig_offset = [v for f, v in flds(man) if f == 11]
sig_size = [v for f, v in flds(man) if f == 12]
print('manifest sig_offset field11:', sig_offset, 'sig_size field12:', sig_size)
print('header ssl:', ssl, 'blob total:', total, 'len', len(payload))

# key from the reproducible build
pk8 = serialization.load_der_private_key(open(B.PRIV_KEY_PATH, 'rb').read(), password=None)
pub = pk8.public_key()

# 1. verify with cryptography (should match what script did)
meta_hash = hashlib.sha256(payload[:24 + ml]).digest()
packed_meta_sig = payload[24 + ml:24 + ml + ssl]

# try every plausible signed region
import itertools
regions = {
    'header+manifest': payload[:24 + ml],
    'header+manifest+metasig': payload[:24 + ml + ssl],
    'header+manifest+metasig+blobs': payload[:24 + ml + ssl + total],
    'manifest only': man,
}
for name, r in regions.items():
    for hname, halg in [('sha256', hashes.SHA256()), ('sha1', hashes.SHA1())]:
        try:
            pub.verify(packed_meta_sig, hashlib.new(hname, r).digest(), padding.PKCS1v15(),
                       halg)
            print('MATCH:', name, hname)
        except Exception:
            pass

# 2. decrypt the RSA signature to see the DigestInfo
n = pub.public_numbers().n
e = pub.public_numbers().e
sig_int = int.from_bytes(packed_meta_sig, 'big')
em = pow(sig_int, e, n).to_bytes(256, 'big')
print('EM header:', em[:2].hex(), 'len', len(em))
print('EM tail:', em[-40:].hex())
# find digestinfo
print('DigestInfo prefix (hex):', em[2:20].hex())
# sha256 DigestInfo = 3031300d060960864801650304020105000420
# sha1   DigestInfo = 3021300906052b0e03021a05000414
