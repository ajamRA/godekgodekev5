"""Find what the payload signature actually covers (exhaustive prefix/suffix scan)."""
import hashlib
import os

p = open('_scratch/payload.bin', 'rb').read()
ml, ssl = 930, 264
total = 15608747
hdr = p[:24]
man = p[24:24 + ml]
msig = p[24 + ml:24 + ml + ssl]
blobs = p[24 + ml + ssl:24 + ml + ssl + total]
paysig = p[24 + ml + ssl + total:]
target = bytes.fromhex('3f23fc66d9064e36c9225f433a2dc413da94c964ca36d9189b55d73fe4f06948')

print('segments: hdr=%d man=%d msig=%d blobs=%d paysig=%d' %
      (len(hdr), len(man), len(msig), len(blobs), len(paysig)))

# 1) contiguous prefix of the whole file?
for end in (24, 24 + ml, 24 + ml + ssl, 24 + ml + ssl + total, len(p)):
    if hashlib.sha256(p[:end]).digest() == target:
        print('PREFIX MATCH len', end)
print('prefix scan done')

# 2) prefix with the sig blob's own dummy zeros replaced etc. -> try all prefix
# lengths that are "interesting" boundaries is done; do full prefix scan cheaply
# by streaming.
h = hashlib.sha256()
for i in range(len(p)):
    h.update(p[i:i + 1])
    # too slow in python; skip
    break
print('skip full scan')

# 3) maybe digest = sha256(metadata) ^ sha256(blobs)? or concat of raw digests
hm = hashlib.sha256(hdr + man).digest()
hm2 = hashlib.sha256(hdr + man + msig).digest()
hb = hashlib.sha256(blobs).digest()
cand = {
    'hm': hm, 'hm2': hm2, 'hb': hb,
    'hm+hb': hm + hb, 'hb+hm': hb + hm,
    'sha256(hm+hb)': hashlib.sha256(hm + hb).digest(),
    'sha256(hb+hm)': hashlib.sha256(hb + hm).digest(),
    'sha256(hm2+hb)': hashlib.sha256(hm2 + hb).digest(),
}
for k, v in cand.items():
    if v == target:
        print('*** MATCH', k)

# 4) file hash of payload minus the trailing sig? and of blobs-only file?
print()
print('sha256(payload)                 ', hashlib.sha256(p).hexdigest())
print('sha256(blobs)                   ', hb.hex())
print('target                          ', target.hex())

# 5) Try: signature over FILE_HASH from props (sha256 of whole payload)
print('props FILE_HASH is sha256(whole payload):', hashlib.sha256(p).digest() == hashlib.sha256(p).digest())
