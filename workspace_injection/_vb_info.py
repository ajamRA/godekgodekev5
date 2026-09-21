import sys, os
sys.path.insert(0, '.')
import avbtool

t = avbtool.Avb()
for f in ('D:/apps/emas-ota/parts/vbmeta.img',
          'D:/apps/emas-ota/parts/vbmeta_system.img',
          'D:/apps/emas-ota/parts/vbmeta_vendor.img'):
    print('##### %s  %d bytes' % (f, os.path.getsize(f)))
    try:
        t.info_image(f, sys.stdout, True)
    except Exception as e:
        import traceback
        traceback.print_exc()
    print()
