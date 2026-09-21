import zipfile, hashlib

z = zipfile.ZipFile('E:/update.zip')
zo = zipfile.ZipFile('E:/update.zip.OLD_backup')

for label, zz in (('NEW (ours)', z), ('OLD (.OLD_backup)', zo)):
    print('===== %s =====' % label)
    for name in ('upgrade.conf',):
        b = zz.read(name)
        print('  %s sha256=%s' % (name, hashlib.sha256(b).hexdigest()[:24]))
        print('  ------------------------------')
        print(b.decode('utf-8', 'replace'))
        print('  ------------------------------')
    b = zz.read('META-INF/com/android/metadata')
    print('  metadata sha256=%s' % hashlib.sha256(b).hexdigest()[:24])
    print(b.decode('utf-8', 'replace'))
    print()
