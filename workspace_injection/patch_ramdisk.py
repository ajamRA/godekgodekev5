import struct, gzip, os

BOOT_IN = r'D:\apps\emas-ota\workspace_injection\original_backup\boot.img'
PATCHED_DIR = r'D:\apps\emas-ota\workspace_injection\patched_build'
BOOT_OUT = os.path.join(PATCHED_DIR, 'boot.img')

with open(BOOT_IN, 'rb') as f:
    raw = f.read()

hdr = raw[:2048]
kernel_size = struct.unpack_from('<I', hdr, 8)[0]
ramdisk_size = struct.unpack_from('<I', hdr, 16)[0]
page_size = struct.unpack_from('<I', hdr, 36)[0]

def page_align(val, page):
    return (val + page - 1) // page * page

kernel_offset = page_size
kernel_pages = page_align(kernel_size, page_size)
ramdisk_offset = kernel_offset + kernel_pages

kernel_bytes = raw[kernel_offset : kernel_offset + kernel_size]
ramdisk_gz = raw[ramdisk_offset : ramdisk_offset + ramdisk_size]

cpio = bytearray(gzip.decompress(ramdisk_gz))
pos = 105404
assert cpio[pos:pos+6] == b'070701', 'CPIO magic mismatch!'

namesize = int(cpio[pos+94:pos+102], 16)
old_filesize = int(cpio[pos+54:pos+62], 16)
filename_end = pos + 110 + namesize

# Correct CPIO newc padding formula:
# Header (110 bytes) + filename (namesize) is padded to 4-byte boundary
old_data_start = (filename_end + 3) & ~3
old_data_end = old_data_start + old_filesize
old_next_header = (old_data_end + 3) & ~3
assert cpio[old_next_header : old_next_header + 6] == b'070701', 'Old next header invalid!'

old_data = bytes(cpio[old_data_start : old_data_end])
cleaned_text = old_data.rstrip(b'\x00\r\n ').decode('utf-8')

lines = []
for l in cleaned_text.splitlines():
    l = l.strip()
    if l.startswith('ro.secure='): lines.append('ro.secure=0')
    elif l.startswith('ro.debuggable='): lines.append('ro.debuggable=1')
    elif l.startswith('ro.adb.secure='): lines.append('ro.adb.secure=0')
    elif l.startswith('persist.sys.molead.usb.mode='): lines.append('persist.sys.molead.usb.mode=adb')
    elif l.startswith('persist.sys.usb.config='): lines.append('persist.sys.usb.config=mtp,adb')
    elif l.startswith('sys.usb.config='): lines.append('sys.usb.config=mtp,adb')
    elif l: lines.append(l)

lines.append('persist.adb.tcp.port=5555')
lines.append('service.adb.tcp.port=5555')
lines.append('sys.usb.config=mtp,adb')

new_text = '\n'.join(lines) + '\n'
new_data_bytes = new_text.encode('utf-8')
assert b'\x00' not in new_data_bytes

new_filesize = len(new_data_bytes)
new_hdr = bytearray(cpio[pos:pos+110])
new_hdr[54:62] = f'{new_filesize:08X}'.encode('ascii')

filename_with_pad = cpio[pos+110 : old_data_start]
pad_len = ((new_filesize + 3) & ~3) - new_filesize
new_padding = b'\x00' * pad_len

new_cpio = bytearray()
new_cpio.extend(cpio[:pos])
new_cpio.extend(new_hdr)
new_cpio.extend(filename_with_pad)
new_cpio.extend(new_data_bytes)
new_cpio.extend(new_padding)
new_cpio.extend(cpio[old_next_header:])

# Verify full archive integrity
check_pos = 0
entries_count = 0
found_prop = False
while check_pos < len(new_cpio) - 110:
    magic = new_cpio[check_pos : check_pos + 6]
    assert magic == b'070701', f'CPIO corruption at {check_pos}!'
    nsize = int(new_cpio[check_pos+94:check_pos+102], 16)
    fsize = int(new_cpio[check_pos+54:check_pos+62], 16)
    name = new_cpio[check_pos+110 : check_pos+110+nsize]
    d_start = (check_pos + 110 + nsize + 3) & ~3
    d_end = d_start + fsize
    next_pos = (d_end + 3) & ~3
    if b'prop.default' in name:
        found_prop = True
        p_data = new_cpio[d_start : d_end]
        assert p_data.endswith(b'sys.usb.config=mtp,adb\n'), 'Tail mismatch!'
        assert b'\x00' not in p_data, 'Null byte found in prop.default!'
    check_pos = next_pos
    entries_count += 1
    if b'TRAILER!!!' in name:
        break

assert found_prop
print(f'Verified {entries_count} CPIO entries without errors.')

new_ramdisk_gz = gzip.compress(new_cpio, compresslevel=9)
new_ramdisk_size = len(new_ramdisk_gz)

new_boot_hdr = bytearray(hdr)
struct.pack_into('<I', new_boot_hdr, 16, new_ramdisk_size)

out = bytearray()
out.extend(new_boot_hdr)
out.extend(kernel_bytes)

pad_kernel = page_align(kernel_size, page_size) - kernel_size
if pad_kernel > 0:
    out.extend(b'\x00' * pad_kernel)

out.extend(new_ramdisk_gz)
pad_ramdisk = page_align(new_ramdisk_size, page_size) - new_ramdisk_size
if pad_ramdisk > 0:
    out.extend(b'\x00' * pad_ramdisk)

rest_offset = ramdisk_offset + page_align(ramdisk_size, page_size)
if rest_offset < len(raw):
    out.extend(raw[rest_offset:])

target_size = len(raw)
if len(out) < target_size:
    out.extend(b'\x00' * (target_size - len(out)))

with open(BOOT_OUT, 'wb') as f:
    f.write(out)

print(f'SUCCESS: Perfectly aligned boot.img created at {BOOT_OUT} ({len(out)} bytes)')
