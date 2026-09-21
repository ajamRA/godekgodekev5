import zipfile, hashlib, struct, sys
sys.path.insert(0, '.')
from payload_dumper import update_metadata_pb2 as pb

NEW = 'E:/update.zip'
OLD = 'E:/update.zip.OLD_backup'


def parse(path):
    p = zipfile.ZipFile(path).read('payload.bin')
    ml = struct.unpack_from('>Q', p, 12)[0]
    ssl = struct.unpack_from('>I', p, 20)[0]
    m = pb.DeltaArchiveManifest()
    m.ParseFromString(p[24:24 + ml])
    return p, ml, ssl, m


p, ml, ssl, mani = parse(NEW)
p2, ml2, ssl2, m2 = parse(OLD)

print('#### NEW (E:/update.zip = ours)  payload.bin=%d ####' % len(p))
for pu in mani.partitions:
    print('   partition:', pu.partition_name, 'ops=', len(pu.operations))
    for op in pu.operations[:3]:
        print('      type=%d data_len=%d blobs=%d' % (
            op.type, op.data_length, len(op.data_sha256_hash)))

print('#### OLD (update.zip.OLD_backup) payload.bin=%d ####' % len(p2))
for pu in m2.partitions:
    print('   partition:', pu.partition_name, 'ops=', len(pu.operations))

print()
print('payload size  old=%d  new=%d  delta=%+d' % (len(p2), len(p), len(p) - len(p2)))
print('manifest_len  old=%d  new=%d' % (ml2, ml))
print('meta_sig_size old=%d  new=%d' % (ssl2, ssl))
print('metadata_size old=%d  new=%d' % (24 + ml2, 24 + ml))

za, zb = zipfile.ZipFile(OLD), zipfile.ZipFile(NEW)
na, nb = set(za.namelist()), set(zb.namelist())
print()
print('only in OLD:', sorted(na - nb))
print('only in NEW:', sorted(nb - na))
common = sorted(na.intersection(nb))
print()
for n in common:
    ha = hashlib.sha256(za.read(n)).hexdigest()
    hb = hashlib.sha256(zb.read(n)).hexdigest()
    tag = 'SAME' if ha == hb else 'DIFF'
    print('  %-42s %-5s %s' % (n, tag, hb[:24]))
