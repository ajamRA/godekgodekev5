import zipfile
import re
import sys

Z = r'E:/IHU801P_log_260910101832/mobilelog.zip'
z = zipfile.ZipFile(Z)
names = z.namelist()
print('=== target files ===')
for n in names:
    if n.endswith('properties') or n.endswith('mblog_history') or 'bootprof' in n:
        print('  ', n, z.getinfo(n).file_size)

def show(n, patterns, limit=40, label=None):
    data = z.read(n).decode('utf-8', 'replace')
    lines = data.splitlines()
    hits = [ln for ln in lines if any(re.search(p, ln, re.I) for p in patterns)]
    print('\n--- %s  (%s)  %d/%d lines matched ---' % (label or n, n, len(hits), len(lines)))
    for ln in hits[:limit]:
        print('   ', ln.strip()[:220])

PROPS = [r'^ro\.debuggable', r'^ro\.secure', r'^ro\.adb\.secure', r'^ro\.build\.type',
         r'^ro\.build\.version', r'^service\.adb', r'^persist\.adb', r'^persist\.service\.adb',
         r'adb_enabled', r'^ro\.boot', r'^sys\.usb\.config', r'tcp\.port']
for n in names:
    if n.endswith('properties'):
        show(n, PROPS, limit=60, label='PROPERTIES')
