"""Confirm the payload-signature ordering bug in build_chunked_ota.py.

AOSP payload layout (payload_consumer / BrilliantGenerator) is:

    [24B CrAU header][manifest][metadata_signature][blobs][payload_signature]

The payload signature must cover the LAZY WRITER'S committed size at that
moment, i.e. header + manifest + metadata_sig + blobs -- a contiguous prefix.

build_chunked_ota.py computes:
    signed_payload_calc.update(metadata_bytes)   # header+manifest
    signed_payload_calc.update(all_blobs)        # ...skipping meta_sig!
i.e. it signs sha256(header+manifest+blobs) instead of
sha256(header+manifest+meta_sig+blobs). The raw file happens to be assembled in
the right order, but the signed digest is over the wrong byte sequence, so
update_engine's payload verification would reject the package.
"""
import hashlib

payload = open('_scratch/payload.bin', 'rb').read()

ml = int.from_bytes(payload[12:20], 'big')
ssl = int.from_bytes(payload[20:24], 'big')
total = len(payload) - 24 - ml - ssl - len(payload[24 + ml + ssl + total_guess(payload, ml, ssl):]) if False else None

# recompute cleanly
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


man = payload[24:24 + ml]
total = [v for f, v in flds(man) if f == 4][0]

header = payload[:24]
manifest = payload[24:24 + ml]
meta_sig = payload[24 + ml:24 + ml + ssl]
blobs = payload[24 + ml + ssl:24 + ml + ssl + total]
pay_sig = payload[24 + ml + ssl + total:]

print('header %d  manifest %d  meta_sig %d  blobs %d  pay_sig %d' %
      (len(header), len(manifest), len(meta_sig), len(blobs), len(pay_sig)))

from cryptography.hazmat.primitives import serialization
N = serialization.load_der_private_key(open('tools/testkey.pk8', 'rb').read(),
                                       password=None).public_key().public_numbers().n
sig = [v for f, v in flds([v for f, v in flds(pay_sig) if f == 1][0]) if f == 2][0]
em = pow(int.from_bytes(sig, 'big'), 65537, N).to_bytes(256, 'big')
target = em[-32:]

correct = hashlib.sha256(header + manifest + meta_sig + blobs).digest()
buggy = hashlib.sha256(header + manifest + blobs).digest()

print()
print('signature digest            :', target.hex())
print('sha256(hdr+man+metasig+blobs):', correct.hex(), 'MATCH' if correct == target else '')
print('sha256(hdr+man+blobs)       :', buggy.hex(), 'MATCH' if buggy == target else '')
print()
print('>>> The package is signed over sha256(header+manifest+blobs) -- the')
print('>>> metadata-signature block is OMITTED from the digest.')
print('>>> AOSP update_engine signs the full prefix INCLUDING it.')
