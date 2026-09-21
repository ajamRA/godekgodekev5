import re

b = open('../parts/lk.img', 'rb').read()
runs = [r.decode('ascii', 'replace') for r in re.findall(rb'[\x20-\x7e]{5,}', b)]

print('===== LK: AVB / unlock / verification state strings =====')
for key in ('lock_state', 'unlock', 'oem_avb_key', 'vbmeta_avb_key',
            'persist item', 'green', 'orange', 'yellow', 'red',
            'AVB_AB_MAGIC', 'boot_ctrl', 'ROLLBACK', 'rollback'):
    hits = [t for t in runs if key.lower() in t.lower()]
    if hits:
        print('--- %s (%d)' % (key, len(hits)))
        for h in hits[:10]:
            print('     ', h[:140])

print()
print('===== LK: how flags are used =====')
for key in ('ALLOW_VERIFICATION_ERROR', 'DISABLE_VERIFICATION',
            'HASHTREE_DISABLED', 'VERIFICATION_DISABLED', 'flags'):
    hits = [t for t in runs if key.lower() in t.lower()]
    print('--- %s (%d)' % (key, len(hits)))
    for h in hits[:8]:
        print('     ', h[:140])

print()
print('===== LK: boot partition names / slots =====')
for key in ('boot_a', 'boot_b', 'system_a', 'vendor_a', 'vbmeta_system',
            'vbmeta_vendor', 'vbmeta'):
    hits = [t for t in runs if key == t or t.startswith(key)]
    print('--- %s (%d): %s' % (key, len(hits), hits[:6]))
