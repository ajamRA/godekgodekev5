import re

b = open('../parts/lk.img', 'rb').read()

# extract all ASCII runs >= 6
runs = re.findall(rb'[\x20-\x7e]{6,}', b)
print('total ascii runs:', len(runs))
txt = [r.decode('ascii', 'replace') for r in runs]

pats = ['avb', 'verify', 'unbootable', 'verify slot', 'AVB_SLOT',
        'boot.img', 'dtbo', 'ramdisk', 'mboot', 'load_bootimg',
        'slot_verify', 'verification', 'allow_verification',
        'boot_a', 'boot_b', 'ANDROID', 'kernel', 'header_version']
print()
for p in pats:
    hits = [t for t in txt if p.lower() in t.lower()]
    print('--- %-24s %d hits' % (p, len(hits)))
    for h in hits[:12]:
        print('      %s' % h[:150])
