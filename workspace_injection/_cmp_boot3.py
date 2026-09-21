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
    # signature_offset -> payload sig starts right after blobs
    psig = struct.unpack_from('>Q', p, 12 + 8 + 4 + ml + ssl)[0]
    base = 24 + ml + ssl
    out = bytearray(P)
    off = base
    ok_all = True
    part = m.partitions[0]
    print('%s: ops=%d  blobs_region=%d  payload_sig_off=%d' % (
        label, len(part.operations), psig - base if psig else 0, psig))
    for i, op in enumerate(part.operations):
        raw = p[off: off + op.data_length]
        off += op.data_length
        if op.type == 9:
            raw = bz2.decompress(raw)
        elif op.type == 10:
            raw = lzma.decompress(raw)
        # sha256 of op data (over the on-the-wire bytes) -- only REPLACE has hash
        dst = op.dst_extents[0]
        pos = dst.start_block * 4096
        out[pos:pos + len(raw)] = raw
        h = hashlib.sha256(raw).hexdigest()
        ok = (len(op.data_sha256_hash) == 0) or (op.data_sha256_hash == hashlib.sha256(raw).digest())
        if not ok:
            ok_all = False
            print('   op[%2d] type=%d SHA MISMATCH' % (i, op.type))
    print('   all op sha256 ok:', ok_all)
    return bytes(out)


new = load('E:/update.zip', 'NEW  E:/update.zip')
old = load('E:/update.zip.OLD_backup', 'OLD  .OLD_backup')

for name in ('patched_build/boot.img', 'original_backup/boot.img'):
    b = open(name, 'rb').read()
    print()
    print('%-28s %s' % (name, hashlib.sha256(b).hexdigest()))
    print('   == NEW boot:', hashlib.sha256(b).digest() == hashlib.sha256(new).digest())
    print('   == OLD boot:', hashlib.sha256(b).digest() == hashlib.sha256(old).digest())

print()
print('NEW boot sha256:', hashlib.sha256(new).hexdigest())
print('OLD boot sha256:', hashlib.sha256(old).hexdigest())
print('NEW boot == OLD boot:', hashlib.sha256(new).digest() == hashlib.sha256(old).digest())
