import struct
import gzip
import hashlib
import lzma
import bz2
import zipfile

Z = 'output/update.zip'


def rv(b, i):
    v = 0
    sh = 0
    while True:
        x = b[i]
        i += 1
        v |= (x & 0x7F) << sh
        sh += 7
        if not x & 0x80:
            break
    return v, i


def flds(buf):
    out = []
    i = 0
    while i < len(buf):
        tag, i = rv(buf, i)
        f = tag >> 3
        wt = tag & 7
        if wt == 0:
            v, i = rv(buf, i)
            out.append((f, v))
        elif wt == 2:
            ln, i = rv(buf, i)
            out.append((f, buf[i:i + ln]))
            i += ln
        else:
            raise ValueError(str(wt))
    return out


payload = zipfile.ZipFile(Z).read('payload.bin')
ml = int.from_bytes(payload[12:20], 'big')
msl = int.from_bytes(payload[20:24], 'big')
body = payload[24:24 + ml]
base = 24 + ml + msl
part = [v for f, v in flds(body) if f == 13][0]
img = bytearray()
for o in [v for f, v in flds(part) if f == 8]:
    d = {f: v for f, v in flds(o)}
    blob = payload[base + d[2]:base + d[2] + d[3]]
    if d[1] == 1:
        img += bz2.decompress(blob)
    else:
        img += lzma.decompress(blob, format=lzma.FORMAT_XZ)

img = bytes(img)
print('boot.img size:', len(img), 'magic:', img[:8])

ksz = struct.unpack_from('<I', img, 8)[0]
rsz = struct.unpack_from('<I', img, 16)[0]
page = struct.unpack_from('<I', img, 36)[0]
koff = page
kpages = (ksz + page - 1) // page * page
roff = koff + kpages
cmdline = img[64:64 + 512].split(b'\x00')[0]
print('kernel sz:', ksz, 'ramdisk sz:', rsz, 'page:', page)
print('cmdline:', cmdline.decode(errors='replace'))

cpio = gzip.decompress(img[roff:roff + rsz])
print('cpio size:', len(cpio))

# walk cpio
entries = []
i = 0
while i + 110 <= len(cpio):
    mag = cpio[i:i + 6]
    if mag not in (b'070701', b'070702'):
        nxt = cpio.find(b'070701', i)
        if nxt == -1 or nxt - i > 8192:
            break
        i = nxt
        continue
    h = cpio[i:i + 110]
    fs = int(h[54:62], 16)
    ns = int(h[94:102], 16)
    no = i + 110
    name = cpio[no:no + ns].split(b'\x00', 1)[0].decode('utf-8', 'replace')
    do = (no + ns + 3) // 4 * 4
    data = cpio[do:do + fs]
    entries.append((name, data))
    i = (do + fs + 3) // 4 * 4
    if name == 'TRAILER!!!':
        break

print('cpio entries:', len(entries))
names = [n for n, _ in entries]
for want in ['prop.default', 'default.prop', 'p', 'w', 'force_debuggable',
             'debug_ramdisk/adb_debug.prop', 'init.rc', 'system/bin/init']:
    print('  present %-28s %s' % (want, want in names))

print('\n=== p (=overlay prop.default) ===')
for n, d in entries:
    if n == 'p':
        txt = d.decode('latin1')
        for line in txt.splitlines():
            if any(k in line for k in ('debuggable', 'secure', 'build.type', 'tcp.port')):
                print('  ', line)

print('\n=== debug_ramdisk/adb_debug.prop ===')
for n, d in entries:
    if n == 'debug_ramdisk/adb_debug.prop':
        print('  ', d.decode('latin1').replace('\n', '\n   '))

print('\n=== w (=overlay blank_screen.rc) ===')
for n, d in entries:
    if n == 'w':
        print('  ', d.decode('latin1').replace('\n', '\n   '))

print('\n=== init.rc tail (adb section) ===')
for n, d in entries:
    if n == 'init.rc':
        t = d.decode('latin1')
        idx = t.find('service.adb.tcp.port')
        print('  ...' + t[max(0, idx - 200):idx + 400].replace('\n', '\n   ') if idx >= 0 else '  NOT FOUND')

print('\n=== force_debuggable marker present:', 'force_debuggable' in names)

# check init binary patches
for n, d in entries:
    if n == 'system/bin/init':
        print('\n=== system/bin/init checks ===')
        print('  sha256:', hashlib.sha256(d).hexdigest())
        print('  has /first_stage_ramdisk/p string:', b'/first_stage_ramdisk/p\x00' in d)
        print('  has old /system/etc/prop.default str:', b'/system/etc/prop.default\x00' in d)
        off = d.find(b'/first_stage_ramdisk/p\x00')
        print('  /first_stage_ramdisk/p at 0x%x' % off)
        # branch bytes at 0x6b6c8
        print('  0x6b6c8 bytes:', d[0x6b6c8:0x6b6cc].hex())
        # mov w19,#1 at 0x5C77C
        print('  0x5c77c bytes:', d[0x5C77C:0x5C780].hex(), '(expect 33008052)')
