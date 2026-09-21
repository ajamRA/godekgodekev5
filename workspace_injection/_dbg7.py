"""Correct payload diagnosis: slice payload.bin from the zip, not from raw offset."""
import hashlib
import struct
import sys
import zipfile

sys.path.insert(0, '.')
from payload_dumper import update_metadata_pb2 as pb
from cryptography.hazmat.primitives import serialization

zf = zipfile.ZipFile('output/update.zip')
p = zf.read('payload.bin')
print('payload.bin length:', len(p), '(expected 15610229)')
assert len(p) == 15610229

ml = struct.unpack_from('>Q', p, 12)[0]
ssl = struct.unpack_from('>I', p, 20)[0]
mani = pb.DeltaArchiveManifest()
mani.ParseFromString(p[24:24 + ml])
tb = mani.signatures_offset
so = 24 + ml + ssl + tb
sig = pb.Signatures()
sig.ParseFromString(p[so:so + mani.signatures_size])
N = serialization.load_der_private_key(open('tools/testkey.pk8', 'rb').read(),
                                       password=None).public_key().public_numbers().n
em = pow(int.from_bytes(sig.signatures[0].data, 'big'), 65537, N).to_bytes(256, 'big')
t = em[-32:]

hdr = p[:24]
man = p[24:24 + ml]
meta_real = p[24 + ml:24 + ml + ssl]
blobs = p[24 + ml + ssl:24 + ml + ssl + tb]

dummy = pb.Signatures()
ds = dummy.signatures.add()
ds.version = 1
ds.data = b'\x00' * 256

print('ml=%d ssl=%d tb=%d so=%d' % (ml, ssl, tb, so))
print('signature covers:', t.hex())
print()
cands = {
    'hdr+man+meta+blobs    (AOSP/now)': hdr + man + meta_real + blobs,
    'hdr+man+blobs         (old bug)' : hdr + man + blobs,
    'hdr+man+dummy+blobs'             : hdr + man + dummy.SerializeToString() + blobs,
    'hdr+man+meta'                    : hdr + man + meta_real,
    'hdr+man+meta+blobs+sig'          : p,
    'blobs'                           : blobs,
}
for lbl, msg in cands.items():
    d = hashlib.sha256(msg).digest()
    print('%-34s %s %s' % (lbl, d.hex()[:32], 'MATCH <<<' if d == t else ''))
print()
# also verify metadata signature
metasig = pb.Signatures()
metasig.ParseFromString(p[24 + ml:24 + ml + ssl])
emm = pow(int.from_bytes(metasig.signatures[0].data, 'big'), 65537, N).to_bytes(256, 'big')
print('meta sig covers matches sha256(hdr+man):',
      emm[-32:] == hashlib.sha256(hdr + man).digest())
