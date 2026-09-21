import struct, zlib, sys

b = open('../parts/lk.img', 'rb').read()
print('total file size:', len(b))
print('header magic    :', b[:4].hex())
print('image name      :', b[8:12])

# MTK LK image header (lk_hdr): magic(4) name(12) load_addr(4) img_size(4) mode(4) ...
# Common MTK layout: [0x1D 0x00 ...] — here 88 16 88 58 48 0a 0a 00
for off in range(0, 64):
    print('  off %2d: 0x%08x  %s' % (off, struct.unpack_from('<I', b, off)[0],
                                    b[off:off+8].hex()))

# the ANDROID! at 331732 is the embedded boot img header string of LK's own code/loader
# find compressed payload: look for gzip/lz4/zstd/7z/xz anywhere
import re
for name, sig in (('gzip', b'\x1f\x8b\x08'), ('lz4f', b'\x04\x22\x4d\x18'),
                  ('zstd', b'\x28\xb5\x2f\xfd'), ('xz', b'\xfd7zXZ\x00'),
                  ('7z', b'7z\xbc\xaf\x27\x1c'), ('lzma', b'\x5d\x00\x00')):
    idx = [m.start() for m in re.finditer(re.escape(sig), b)]
    print('%-6s at %s' % (name, idx[:6]))
