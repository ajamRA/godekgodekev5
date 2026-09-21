"""Find the exact signed byte range for the payload signature."""
import hashlib
import itertools

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
            raise ValueError(wt)
    return out


ml = int.from_bytes(payload[12:20], 'big')
ssl = int.from_bytes(payload[20:24], 'big')
man = payload[24:24 + ml]
total = [v for f, v in flds(man) if f == 4][0]

# The manifest is serialized; its byte length may differ from the framing length
# if the writer prefixed it. Show first bytes to see the real protobuf start.
print('payload[0:24] =', payload[:24])
print('manifest framing len ml =', ml)
print('manifest head   :', man[:16].hex())
print('manifest fields :', [(f, type(v).__name__) for f, v in flds(man)])

sig_blk = payload[24 + ml + ssl + total:]
pay_sig = [v for f, v in flds([v for f, v in flds(sig_blk) if f == 1][0]) if f == 2][0]

from cryptography.hazmat.primitives import serialization
N = serialization.load_der_private_key(open('tools/testkey.pk8', 'rb').read(),
                                       password=None).public_key().public_numbers().n
em = pow(int.from_bytes(pay_sig, 'big'), 65537, N).to_bytes(256, 'big')
target = em[-32:]
print('\nsigned digest:', target.hex())

# try every plausible (start, end) combination
bounds = {
    '0': 0, 'ml0': 24, 'ml+magic': 24, 'end_manifest': 24 + ml,
    'end_metasig': 24 + ml + ssl, 'end_blobs': 24 + ml + ssl + total,
    'end_all': len(payload),
    'blobs_start': 24 + ml + ssl,
}
print()
for (na, a), (nb, b) in itertools.product(bounds.items(), repeat=2):
    if b <= a:
        continue
    if hashlib.sha256(payload[a:b]).digest() == target:
        print('*** MATCH range [%d:%d]  %s -> %s' % (a, b, na, nb))
print('done scan')
