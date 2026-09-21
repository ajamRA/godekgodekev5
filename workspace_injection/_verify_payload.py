import zipfile
import lzma
import hashlib

Z = 'output/update.zip'
z = zipfile.ZipFile(Z)
payload = z.read('payload.bin')


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
            raise ValueError(f'wt {wt}')
    return out


ml = int.from_bytes(payload[12:20], 'big')
msl = int.from_bytes(payload[20:24], 'big')
body = payload[24:24 + ml]
base = 24 + ml + msl
part = [v for f, v in flds(body) if f == 13][0]
ops = [v for f, v in flds(part) if f == 8]

out = bytearray()
TYPE = {0: 'REPLACE', 1: 'REPLACE_BZ', 2: 'REPLACE_XZ', 8: 'PUFFDIFF'}
for i, o in enumerate(ops):
    d = {f: v for f, v in flds(o)}
    typ, doff, dlen = d[1], d[2], d[3]
    blob = payload[base + doff:base + doff + dlen]
    if typ == 2:
        dec = lzma.decompress(blob, format=lzma.FORMAT_XZ)
    elif typ == 1:
        import bz2
        dec = bz2.decompress(blob)
    else:
        dec = lzma.decompress(blob, format=lzma.FORMAT_XZ)
    print('op%02d %-10s dec=%-9d sha256(blob)=%s' % (i, TYPE.get(typ), len(dec), hashlib.sha256(blob).hexdigest()[:16]))
    out += dec

out = bytes(out)
orig = open('patched_build/boot.img', 'rb').read()
print('\nreconstructed size:', len(out))
print('reconstructed sha256:', hashlib.sha256(out).hexdigest())
print('patched_build   sha:', hashlib.sha256(orig).hexdigest())
print('IDENTICAL:', out == orig)

if out != orig:
    # find first divergence
    n = min(len(out), len(orig))
    for i in range(n):
        if out[i] != orig[i]:
            print('first diff at', i, hex(i), out[i], orig[i])
            break
    else:
        print('common prefix identical up to', n)

# verify op sha256 field 8 == sha256(decoded) for REPLACE ops
ok = True
off = 0
for i, o in enumerate(ops):
    d = {f: v for f, v in flds(o)}
    if d[1] in (0, 1, 2):
        blob = payload[base + d[2]:base + d[2] + d[3]]
        dec = lzma.decompress(blob, format=lzma.FORMAT_XZ) if d[1] == 2 else blob
        h = hashlib.sha256(dec).digest()
        print('op%02d field8==sha256(decoded): %s' % (i, h == d[8]))
        ok &= (h == d[8])
print('ALL REPLACE OP HASHES VALID:', ok)
