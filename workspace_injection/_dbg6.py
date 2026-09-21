"""Determine the message that was REALLY signed for the payload signature."""
import hashlib

p = open('_scratch/payload.bin', 'rb').read()
ml, ssl, total = 930, 264, 15608747
hdr = p[:24]
man = p[24:24 + ml]
msig = p[24 + ml:24 + ml + ssl]
blobs = p[24 + ml + ssl:24 + ml + ssl + total]
paysig = p[24 + ml + ssl + total:]
target = bytes.fromhex('3f23fc66d9064e36c9225f433a2dc413da94c964ca36d9189b55d73fe4f06948')

print('target            :', target.hex())
print('sha256(hdr+man)   :', hashlib.sha256(hdr + man).hexdigest())
print('sha256(hdr+man+blobs):', hashlib.sha256(hdr + man + blobs).hexdigest())
print()
print('Is target == sha256(hdr+man) ?', hashlib.sha256(hdr + man).digest() == target)
print('Is target == sha256(hdr+man+blobs) ?', hashlib.sha256(hdr + man + blobs).digest() == target)
print()
# could it be the FIRST signature (metadata) was reused?
print('meta sig target   : 26e553109963c14a26bdf31e35aa4753e7a012d91220a1c319d40eb68be1d1ec')
print()
# Maybe signature is over the SHA of the FULL payload minus signature (i.e. the
# "prefix" the way update_engine does it) but the ordering in the file differs:
print('sha256(p minus trailing paysig):', hashlib.sha256(p[:-len(paysig)]).hexdigest())
print('sha256(p minus trailing sigs)  :', hashlib.sha256(p[:-len(paysig) - ssl]).hexdigest())
