import zipfile, hashlib, struct, sys
sys.path.insert(0, '.')
from payload_dumper import update_metadata_pb2 as pb

# Rebuild boot.img from the payload in E:/update.zip and hash it
import bz2

z = zipfile.ZipFile('E:/update.zip')
p = z.read('payload.bin')
ml = struct.unpack_from('>Q', p, 12)[0]
ssl = struct.unpack_from('>I', p, 20)[0]
total = struct.unpack_from('>Q', p, 12 + 8 + 4 + ml + ssl)[0]
m = pb.DeltaArchiveManifest()
m.ParseFromString(p[24:24 + ml])

base = 24 + ml + ssl
off = 0
out = b''
nrep = 0
for op in m.partitions[0].operations:
    t = op.type
    if t == 8:  # REPLACE
        ln = op.data_length
        out += p[base + off: base + off + ln]
        off += ln
        nrep += 1
    elif t == 9:  # REPLACE_BZ
        ln = op.data_length
        out += bz2.decompress(p[base + off: base + off + ln])
        off += ln
        nrep += 1
    elif t == 10:  # REPLACE_XZ
        import lzma
        ln = op.data_length
        out += lzma.decompress(p[base + off: base + off + ln])
        off += ln
        nrep += 1

print('rebuilt boot.img size :', len(out))
print('rebuilt boot.img sha256:', hashlib.sha256(out).hexdigest())
print()
for name in ('patched_build/boot.img', 'original_backup/boot.img'):
    try:
        b = open(name, 'rb').read()
        print('%-30s %d  %s' % (name, len(b), hashlib.sha256(b).hexdigest()))
        print('   MATCH payload boot.img:', hashlib.sha256(b).digest() == hashlib.sha256(out).digest())
    except FileNotFoundError:
        print(name, 'NOT FOUND')
