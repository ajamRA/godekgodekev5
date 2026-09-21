import zipfile, hashlib, struct, sys, bz2, lzma
sys.path.insert(0, '.')
from payload_dumper import update_metadata_pb2 as pb


def load(path):
    p = zipfile.ZipFile(path).read('payload.bin')
    ml = struct.unpack_from('>Q', p, 12)[0]
    ssl = struct.unpack_from('>I', p, 20)[0]
    m = pb.DeltaArchiveManifest()
    m.ParseFromString(p[24:24 + ml])
    base = 24 + ml + ssl
    blobbytes = b''
    for op in m.partitions[0].operations:
        raw = p[base: base + op.data_length]
        base += op.data_length
        if op.type == 9:
            raw = bz2.decompress(raw)
        elif op.type == 10:
            raw = lzma.decompress(raw)
        blobbytes += raw
    return blobbytes


new = load('E:/update.zip')
old = load('E:/update.zip.OLD_backup')
patched = open('patched_build/boot.img', 'rb').read()
orig = open('original_backup/boot.img', 'rb').read()

print('payload NEW boot stream : %d bytes  sha256 %s' % (len(new), hashlib.sha256(new).hexdigest()))
print('payload OLD boot stream : %d bytes  sha256 %s' % (len(old), hashlib.sha256(old).hexdigest()))
print()
for nm, b in (('patched_build/boot.img', patched), ('original_backup/boot.img', orig)):
    print('STATIC %-26s len=%d sha256=%s' % (nm, len(b), hashlib.sha256(b).hexdigest()))
print()

# find the real image inside each stream
for label, s in (('NEW', new), ('OLD', old)):
    print('--- %s stream ---' % label)
    print('  first 16 bytes:', s[:16].hex())
    print('  ANDROID! magic at 0 :', s[:8] == b'ANDROID!')
    for tag, b in (('patched', patched), ('orig', orig)):
        idx = s.find(b[:256])
        print('  contains %s boot header? %s' % (tag, idx))
        if idx >= 0:
            seg = s[idx:idx + len(b)]
            print('     at offset %d, len %d, sha256 %s, MATCH=%s' % (
                idx, len(seg), hashlib.sha256(seg).hexdigest()[:24],
                hashlib.sha256(seg).digest() == hashlib.sha256(b).digest()))
    print()
