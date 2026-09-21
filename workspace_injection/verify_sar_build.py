"""Build and verify an isolated experimental candidate, not vehicle readiness."""
import bz2
import gzip
import hashlib
import lzma
from pathlib import Path
import struct
import zipfile
import patch_boot_with_hook as patcher
import build_chunked_ota as ota
from model_hook_176 import build

ROOT = Path(__file__).resolve().parent
protected = [ROOT/'output/update.zip', ROOT/'patched_build/boot.img',
             ROOT/'original_backup/boot.img']
snapshot = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
assert not patcher.SAR_HANDOFF_VERIFIED
try:
    assert not Path(patcher.OUT_BOOT).exists(), 'Candidate already exists; preserve it'
    patcher.main(verification_candidate=True)
    image = Path(patcher.OUT_BOOT).read_bytes()
    assert len(image) == 33554432
    page = struct.unpack_from('<I', image, 36)[0]
    kernel = struct.unpack_from('<I', image, 8)[0]
    size = struct.unpack_from('<I', image, 16)[0]
    offset = page + ((kernel + page - 1)//page)*page
    archive = gzip.decompress(image[offset:offset+size])
    entries = {}; i = 0
    while i + 110 <= len(archive):
        h = archive[i:i+110]
        assert h[:6] == b'070701'
        ns, ds = int(h[94:102],16), int(h[54:62],16)
        name = archive[i+110:i+110+ns-1].decode()
        off = (i+110+ns+3)&~3
        assert name not in entries
        entries[name] = (int(h[14:22],16), archive[off:off+ds])
        i = (off+ds+3)&~3
        if name == 'TRAILER!!!': break
    assert entries['default.prop'][0] & 0o170000 == 0o120000
    assert entries['default.prop'][1] == b'prop.default'
    for name in ('first_stage_ramdisk/p','first_stage_ramdisk/w'):
        assert entries[name][0] & 0o170000 == 0o100000
    assert 'p' not in entries and 'w' not in entries
    assert b'service.adb.tcp.port 5555' in entries['first_stage_ramdisk/w'][1]
    assert b'start adbd' in entries['first_stage_ramdisk/w'][1]
    hook = build()[0]
    assert entries['system/bin/init'][1][0xa9f50:0xa9f50+len(hook)] == hook
    original = protected[2].read_bytes()
    assert image[1632:1644] == original[1632:1644]
    dtbo_size, dtbo_offset = struct.unpack_from('<IQ', original, 1632)
    if dtbo_size:
        assert image[dtbo_offset:dtbo_offset+dtbo_size] == original[dtbo_offset:dtbo_offset+dtbo_size]
    candidate = Path(ota.build_sar_candidate(patcher.OUT_BOOT))
    with zipfile.ZipFile(candidate) as z, zipfile.ZipFile(protected[0]) as old:
        for name in old.namelist():
            if name not in ('payload.bin','payload_properties.txt'):
                assert z.read(name) == old.read(name), 'Extra differs: '+name
        d = z.read('payload.bin')
    ml = struct.unpack_from('>Q',d,12)[0]; ms = struct.unpack_from('>I',d,20)[0]
    m = ota.update_metadata_pb2.DeltaArchiveManifest(); m.ParseFromString(d[24:24+ml])
    assert len(m.partitions)==1 and m.partitions[0].partition_name=='boot'
    rebuilt = bytearray(len(image)); base = 24+ml+ms
    for op in m.partitions[0].operations:
        blob = d[base+op.data_offset:base+op.data_offset+op.data_length]
        assert hashlib.sha256(blob).digest() == op.data_sha256_hash
        raw = (bz2.decompress(blob) if op.type==ota.update_metadata_pb2.InstallOperation.REPLACE_BZ else lzma.decompress(blob))
        pos=0
        for ex in op.dst_extents:
            length=ex.num_blocks*m.block_size; off=ex.start_block*m.block_size
            rebuilt[off:off+length]=raw[pos:pos+length]; pos+=length
        assert pos==len(raw)
    assert rebuilt == image
    assert hashlib.sha256(image).digest()==m.partitions[0].new_partition_info.hash
    print('PASS: CPIO, hook bytes, boot size, DTBO, OTA signatures, extras, reconstructed boot')
    for p in (Path(patcher.OUT_BOOT), candidate):
        print(str(p), p.stat().st_size, hashlib.sha256(p.read_bytes()).hexdigest())
finally:
    for p, digest in snapshot.items():
        assert hashlib.sha256(p.read_bytes()).hexdigest() == digest, 'Protected artifact changed: '+str(p)
    print('PASS: protected artifacts unchanged; SAR_HANDOFF_VERIFIED remains False')
