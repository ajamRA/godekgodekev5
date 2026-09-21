# Rolling Code Generator for Geely EX2 (EX2-HU)
import sys, hashlib, datetime, time

def get_code(ihuid, salt, slot):
    h = hashlib.md5((slot + ihuid + salt).encode('utf-8')).hexdigest()
    dec = str(int(h, 16))[::-1]
    return dec[0::2][:6]

ihuid = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith('-') else '021600000000000000000000'
watch = '-w' in sys.argv or '--watch' in sys.argv

tz = datetime.timezone(datetime.timedelta(hours=8))

def display():
    now = datetime.datetime.now(tz)
    base = now.strftime('%Y%m%d%H%M')
    last = int(base[-1])
    slot = base[:-1] + ('0' if last < 5 else '5')
    in_slot = (now.minute % 5) * 60 + now.second
    left = 300 - in_slot
    
    c_atlas = get_code(ihuid, 'atlas666', slot)
    c_wlan  = get_code(ihuid, 'universal168', slot)
    c_down  = get_code(ihuid, 'clE1o60h', slot)
    
    if watch:
        sys.stdout.write('\033[H\033[J')
    print('=' * 60)
    print('       EX2-HU ROLLING CODE GENERATOR (Geely EX2)      ')
    print('=' * 60)
    print(f' Waktu Sekarang : {now.strftime("%Y-%m-%d %H:%M:%S")} (GMT+8)')
    print(f' Slot Masa      : {slot}  (Baki masa: {left}s)')
    print(f' IHU ID         : {ihuid}')
    print('-' * 60)
    print(f' [1] ATLAS OS / Geely EX2 : {c_atlas}  (Disyorkan untuk Geely EX2)')
    print(f' [2] WLAN Long-Press   : {c_wlan}')
    print(f' [3] USB Downgrade     : {c_down}')
    print('-' * 60)
    print(f' Kod Statik Bypass 1   : BX9527')
    print(f' Kod Statik Bypass 2   : {now.strftime("%Y%m%d")}aco')
    print('=' * 60)
    if watch:
        print(' [Mod Pantau Aktif] Mengemas kini setiap saat... (Ctrl+C keluar)')

if watch:
    try:
        while True:
            display()
            time.sleep(1)
    except KeyboardInterrupt:
        pass
else:
    display()
