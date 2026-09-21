import zipfile, hashlib, struct, sys, bz2
sys.path.insert(0, '.')
from payload_dumper import update_metadata_pb2 as pb

p = zipfile.ZipFile('E:/update.zip').read('payload.bin')
ml = struct.unpack_from('>Q', p, 12)[0]
ssl = struct.unpack_from('>I', p, 20)[0]
m = pb.DeltaArchiveManifest()
m.ParseFromString(p[24:24 + ml])
ops = m.partitions[0].operations

print('ops in manifest :', len(ops))
for i, op in enumerate(ops):
    print('  [%2d] type=%d data_len=%-9d raw_len=%-9d dst=%d' % (
        i, op.type, op.data_length, getattr(op, 'data_length', 0), op.dst_extents[0].num_blocks * 4096))

# --- stream through ALL ops in manifest order, verifying sha256 ---
base = 24 + ml + ssl
off = 0
out = b''
for i, op in enumerate(ops):
    t = op.type
    if t in (8, 9, 10):
        ln = op.data_length
        raw = p[base + off: base + off + ln]
        off += ln
        if t == 9:
            raw = bz2.decompress(raw)
        elif t == 10:
            import lzma
            raw = lzma.decompress(raw)
        got = hashlib.sha256(raw).hexdigest()
        exp = op.data_sha256_hash.hex()
        ok = got == exp
        if not ok:
            print('  [%2d] SHA MISMATCH got=%s exp=%s' % (i, got[:16], exp[:16]))
        out += raw

print()
print('total blob bytes consumed:', off)
print('rebuilt raw size        :', len(out))
print('rebuilt sha256          :', hashlib.sha256(out).hexdigest())
print()
P = 33554432
if len(out) < P:
    out = out + b'\x00' * (P - len(out))
    print('padded to %d with zeros' % P)
print('padded sha256           :', hashlib.sha256(out).hexdigest())
for name in ('patched_build/boot.img', 'original_backup/boot.img'):
    b = open(name, 'rb').read()
    print('%-28s %s  MATCH=%s' % (name, hashlib.sha256(b).hexdigest()[:16],
                                 hashlib.sha256(b).digest() == hashlib.sha256(out).digest()))
