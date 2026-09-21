import zipfile, hashlib, struct, sys, bz2, lzma
sys.path.insert(0, '.')
from payload_dumper import update_metadata_pb2 as pb

P = 33554432


def load(path, label):
    p = zipfile.ZipFile(path).read('payload.bin')
    ml = struct.unpack_from('>Q', p, 12)[0]
    ssl = struct.unpack_from('>I', p, 20)[0]
    m = pb.DeltaArchiveManifest()
    m.ParseFromString(p[24:24 + ml])
    base = 24 + ml + ssl
    out = bytearray(P)
    for i, op in enumerate(m.partitions[0].operations):
        raw = p[base: base + op.data_length]
        base += op.data_length
        if raw[:6] == b'\xfd7zXZ\x00':
            raw = lzma.decompress(raw)
        elif raw[:2] == b'BZ':
            raw = bz2.decompress(raw)
        dst = op.dst_extents[0]
        pos = dst.start_block * 4096
        out[pos:pos + len(raw)] = raw
        print('   %s op[%2d] type=%-2d wire=%-9d raw=%-9d off=%-9d' % (
            label, i, op.type, op.data_length, len(raw), pos))
    return bytes(out)


new = load('E:/update.zip', 'NEW')
old = load('E:/update.zip.OLD_backup', 'OLD')
patched = open('patched_build/boot.img', 'rb').read()
orig = open('original_backup/boot.img', 'rb').read()

print()
print('rebuilt NEW boot.img sha256:', hashlib.sha256(new).hexdigest())
print('rebuilt OLD boot.img sha256:', hashlib.sha256(old).hexdigest())
print()
print('patched_build/boot.img     :', hashlib.sha256(patched).hexdigest())
print('  == NEW boot:', hashlib.sha256(new).digest() == hashlib.sha256(patched).digest())
print('original_backup/boot.img   :', hashlib.sha256(orig).hexdigest())
print('  == OLD boot:', hashlib.sha256(old).digest() == hashlib.sha256(orig).digest())
print()
print('NEW == OLD boot:', hashlib.sha256(new).digest() == hashlib.sha256(old).digest())
