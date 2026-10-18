#!/system/bin/sh
# ==========================================================
# PATCH — service_mode.sh : pembetulan blok AVAS
#
# Masalah asal:
#   1. sound_type_2 tidak di-unmount, jadi slot aktif (2) kekal custom
#   2. Umount sahaja mendedahkan fail 0-bait pada /vendor (dinamik ro)
#      -> perlu bind mount semula fail kilang dari factory_backup
#
# Skrip ini menggantikan blok "# 4. Unmount custom AVAS" dengan versi
# yang menangani ketiga-tiga slot.
#
# Guna:  sh /data/local/window/patch_avas_block.sh
# ==========================================================

SCRIPT="/data/local/window/service_mode.sh"
BAK="${SCRIPT}.before_avas_patch"

if [ ! -f "$SCRIPT" ]; then
    echo "RALAT: $SCRIPT tidak wujud"
    exit 1
fi

if [ ! -f "$BAK" ]; then
    cp "$SCRIPT" "$BAK" || exit 1
    echo "Sandaran dibuat: $BAK"
fi

python3 - "$SCRIPT" << 'PYEOF' || {
    echo "RALAT: python3 tidak tersedia, guna kaedah awk"
    exit 1
}
import sys, re
path = sys.argv[1]
src = open(path, encoding='utf-8').read()

new_block = '''    # 4. Pulihkan AVAS kilang — KETIGA-TIGA slot
    # /vendor ialah partisi dinamik ro dengan fail 0-bait, jadi umount sahaja
    # mendedahkan fail kosong. Kita mesti bind mount semula fail kilang.
    for _slot in 1 2 3; do
        case "$_slot" in
            1) _wav=yinheyinfu.wav ;;
            2) _wav=space.wav ;;
            3) _wav=pixel.wav ;;
        esac
        _tgt="/vendor/etc/avas/sound_type_${_slot}/${_wav}"
        _src="/data/local/avas/factory_backup/${_wav}"
        umount -l "$_tgt" 2>/dev/null
        if [ -f "$_src" ]; then
            mount -o bind "$_src" "$_tgt" 2>/dev/null
            restorecon -F "$_tgt" 2>/dev/null
        fi
        umount -l "/vendor/etc/avas/sound_type_${_slot}/tuning.txt" 2>/dev/null
        umount -l "/vendor/etc/avas/sound_type_${_slot}/bDM_01_01.wav" 2>/dev/null
        umount -l "/vendor/etc/avas/sound_type_${_slot}/tuning_r.txt" 2>/dev/null
    done
    setprop persist.avas.mode 2 2>/dev/null
    AVAS_PID=$(pidof vendor.molead.hardware.avas_service@1.0-service)
    [ -n "$AVAS_PID" ] && kill -9 "$AVAS_PID" 2>/dev/null
    echo "[SERVICE_MODE] AVAS restored to factory on all 3 slots."
'''

pattern = re.compile(r'[ \t]*# 4\. Unmount custom AVAS.*?echo "\[SERVICE_MODE\] AVAS restored to factory mode 2\."\n', re.S)
if not pattern.search(src):
    print("RALAT: blok AVAS asal tidak dijumpai")
    sys.exit(1)

src2 = pattern.sub(new_block, src, count=1)
open(path, 'w', encoding='utf-8').write(src2)
print("Blok AVAS diganti.")
PYEOF

if [ $? -ne 0 ]; then
    echo "Tampalan gagal. Skrip asal tidak diubah."
    exit 1
fi

chmod 755 "$SCRIPT"
echo "Selesai. Sahkan dengan:"
echo "  sh $SCRIPT status"
echo "  mount | grep avas"
