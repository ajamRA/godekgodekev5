"""Exhaustively identify what build_chunked_ota.py actually signed."""
import hashlib

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
print('ml=%d ssl=%d total=%d fileend=%d  trailing=%d' %
      (ml, ssl, total, len(payload), 24 + ml + ssl + total))

header = payload[:24]
manifest = payload[24:24 + ml]
meta_sig = payload[24 + ml:24 + ml + ssl]
blobs = payload[24 + ml + ssl:24 + ml + ssl + total]
pay_sig = payload[24 + ml + ssl + total:]


def sig_of_pb(blob):
    s = [v for f, v in flds(blob) if f == 1][0]
    return [v for f, v in flds(s) if f == 2][0]


from cryptography.hazmat.primitives import serialization
N = serialization.load_der_private_key(open('tools/testkey.pk8', 'rb').read(),
                                       password=None).public_key().public_numbers().n


def digest_signed_by(sigblob):
    sig = sig_of_pb(sigblob)
    em = pow(int.from_bytes(sig, 'big'), 65537, N).to_bytes(256, 'big')
    return em[-32:]


t_pay = digest_signed_by(pay_sig)
t_meta = digest_signed_by(meta_sig)
print('\npayload signature covers:', t_pay.hex())
print('metadata signature covers:', t_meta.hex())
print('sha256(meta)             :', hashlib.sha256(header + manifest).hexdigest())

# Is it a SHA256 over SHA256(meta)||SHA256(blobs)?
hm = hashlib.sha256(header + manifest).digest()
hb = hashlib.sha256(blobs).digest()
cands = {
    'sha256(sha256(meta))': hashlib.sha256(hm).digest(),
    'sha256(sha256(blobs))': hashlib.sha256(hb).digest(),
    'sha256(sha256(meta)+sha256(blobs))': hashlib.sha256(hm + hb).digest(),
    'hm+hb (raw)': hm + hb,
    'sha256(meta+blobs)': hashlib.sha256(header + manifest + blobs).digest(),
}
for k, v in cands.items():
    print('%-40s %s %s' % (k, v.hex()[:32], '  <<< MATCH' if v == t_pay else ''))
