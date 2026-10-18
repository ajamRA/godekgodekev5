#!/usr/bin/env node
/**
 * setup-sandbox.js — Sediakan rootfs palsu yang menyerupai IHU801P.
 *
 * Keadaan awal = kereta NORMAL (semua mod aktif, mount terpasang).
 * Sertakan fail .bak dalam /system/etc/init/ kerana itulah punca sebenar
 * yang menyebabkan Stealth bocor selepas reboot dalam versi lama.
 */

const fs = require('fs');
const path = require('path');

const BASE = __dirname;
const ROOTFS = path.join(BASE, 'rootfs');
const STATE = path.join(BASE, 'state');

const P = (remote) => path.join(ROOTFS, remote.replace(/^\//, ''));

function w(remote, content, mode = 0o644) {
  const p = P(remote);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, content);
  fs.chmodSync(p, mode);
}

function main() {
  fs.rmSync(ROOTFS, { recursive: true, force: true });
  fs.mkdirSync(ROOTFS, { recursive: true });
  fs.mkdirSync(STATE, { recursive: true });

  for (const f of fs.readdirSync(STATE)) {
    fs.unlinkSync(path.join(STATE, f));
  }

  // ---------- /system/etc/init : keadaan ASAL
  w('/system/etc/init/zzz_custom_apps.rc', `# Persistent custom mounts (Netflix, SmartTube, AVAS V8)
on post-fs-data
    mount none /data/local/custom_apps/SmartTube /system/app/ttincar_tiktok_ACO bind rec
    mount none /data/local/custom_apps/Netflix /system/app/JOOXMusic_ACO bind rec
    mount none /data/local/avas/Settings_MOLEAD_v8.apk /system/app/Settings_MOLEAD/Settings_MOLEAD.apk bind rec
    mount none /data/local/avas/v8_clean.wav /vendor/etc/avas/sound_type_3/pixel.wav bind rec


# Telemetry-only daemon (dual path with zzz_telemetry_boot.rc)
    exec_background u:r:su:s0 root root -- /system/bin/sh /data/local/window/start_telemetry.sh

# RetroArch Config Lock
    copy /data/local/retroarch_golden.cfg /data/data/com.retroarch.aarch64/retroarch.cfg
    chmod 0444 /data/data/com.retroarch.aarch64/retroarch.cfg
    chown u0_a90 u0_a90 /data/data/com.retroarch.aarch64/retroarch.cfg
`);

  w('/system/etc/init/zzz_avas_boot.rc', `service avas_boot /system/bin/sh /system/bin/avas_boot.sh
    user root
    group root
    oneshot
    disabled
    seclabel u:r:su:s0

on property:sys.boot_completed=1
    start avas_boot

on property:vendor.avas.bootstrap=1
    start avas_boot
    setprop vendor.avas.bootstrap 0
`);

  w('/system/etc/init/zzz_telemetry_boot.rc', `service telemetry_boot /system/bin/sh /data/local/window/start_telemetry.sh
    user root
    group root
    oneshot
    disabled
    seclabel u:r:su:s0

on property:sys.boot_completed=1
    start telemetry_boot

on property:vendor.telemetry.bootstrap=1
    start telemetry_boot
    setprop vendor.telemetry.bootstrap 0
`);

  // ---------- /system/bin/avas_boot.sh : versi LAMA (tiada semakan bendera)
  w('/system/bin/avas_boot.sh', `#!/system/bin/sh
LOG=/data/local/avas/avas_boot.log
echo "=== AVAS BOOT (OLD, no flag check) ===" > "$LOG"
if [ -f /data/local/window/start_salam.sh ]; then
    sh /data/local/window/start_salam.sh >> "$LOG" 2>&1 &
fi
if [ -f /data/local/window/start_steering_daemon.sh ]; then
    sh /data/local/window/start_steering_daemon.sh >> "$LOG" 2>&1
fi
`, 0o755);

  // ---------- /data/local/window
  w('/data/local/window/service_mode.sh', `#!/system/bin/sh
FLAG="/data/local/window/SERVICE_MODE_ACTIVE"
APPS="org.smarttube.stable com.netflix.mediaclient com.noor.thenoorcar com.cybercat.acbridge"
ACTION="$1"
if [ "$ACTION" = "toggle" ] || [ -z "$ACTION" ]; then
    if [ -f "$FLAG" ]; then ACTION="off"; else ACTION="on"; fi
fi
if [ "$ACTION" = "on" ]; then
    touch "$FLAG"
    echo "[SERVICE_MODE] ON"
    for pkg in $APPS; do echo "Package $pkg new state: disabled-user"; done
elif [ "$ACTION" = "off" ]; then
    rm -f "$FLAG"
    echo "[SERVICE_MODE] OFF"
    for pkg in $APPS; do echo "Package $pkg new state: enabled"; done
elif [ "$ACTION" = "status" ]; then
    if [ -f "$FLAG" ]; then echo "SERVICE_MODE_ACTIVE"; else echo "NORMAL_MODE_ACTIVE"; fi
fi
`, 0o755);

  const simple = {
    'start_steering_daemon.sh': 'Steering Daemon started with PID 900',
    'start_telemetry.sh': 'TelemetryDaemon started',
    'start_salam.sh': 'SalamBoot started',
    'start_megaphone.sh': 'MegaphoneDaemon started',
    'start_door_chime.sh': 'DoorChime started',
    'start_pressure_relief.sh': 'DoorPressureReliefDaemon started',
  };
  for (const [f, msg] of Object.entries(simple)) {
    w(`/data/local/window/${f}`, `#!/system/bin/sh\necho "${msg}"\n`, 0o755);
  }

  // ---------- /data/local/avas
  w('/data/local/avas/resume_avas_on_boot.sh',
    '#!/system/bin/sh\n[ ! -f /data/local/window/.service_mode ] && [ ! -f /data/local/window/SERVICE_MODE_ACTIVE ] || exit 0\n',
    0o755);

  w('/data/local/avas/avas_boot.sh',
    '#!/system/bin/sh\nLOG=/data/local/avas/avas_boot.log\necho "=== AVAS BOOT (data) ===" > "$LOG"\n',
    0o755);

  // ---------- AVAS slot
  const slots = [[1, 'yinheyinfu.wav'], [2, 'space.wav'], [3, 'pixel.wav']];
  for (const [n, name] of slots) {
    w(`/vendor/etc/avas/sound_type_${n}/${name}`, `FACTORY-${name}\n`);
    w(`/data/local/avas/factory_backup/${name}`, `FACTORY-${name}\n`);
    w(`/vendor/etc/avas/sound_type_${n}/tuning.txt`, 'factory-tuning\n');
  }

  w('/data/local/custom_apps/SmartTube/base.apk', 'SMARTTUBE-APK\n');
  w('/data/local/custom_apps/Netflix/base.apk', 'NETFLIX-APK\n');

  // ---------- fail .bak yang jadi punca masalah
  fs.copyFileSync(P('/system/etc/init/zzz_custom_apps.rc'),
                  P('/system/etc/init/zzz_custom_apps.rc.bak'));
  fs.copyFileSync(P('/system/etc/init/zzz_telemetry_boot.rc'),
                  P('/system/etc/init/zzz_telemetry_boot.rc.bak2'));

  // ---------- state awal
  fs.writeFileSync(path.join(STATE, 'disabled_pkgs'), 'com.emas.dvrprobe\n');
  fs.writeFileSync(path.join(STATE, 'running'),
    'com.emas.telemetry.TelemetryDaemon\n' +
    'com.emas.megaphone.MegaphoneDaemon\n' +
    'com.emas.window.DoorPressureReliefDaemon\n' +
    'com.emas.steering.SteeringModeDaemon\n');
  fs.writeFileSync(path.join(STATE, 'mounted'),
    'system/app/ttincar_tiktok_ACO\nsystem/app/JOOXMusic_ACO\n');

  const initCount = fs.readdirSync(P('/system/etc/init')).length;
  console.log(`Sandbox disediakan: ${ROOTFS}`);
  console.log(`  /system/etc/init : ${initCount} fail (termasuk .bak)`);
  console.log(`  pakej disabled   : 1`);
  console.log(`  daemon           : 4`);
  console.log(`  mount slot       : 2`);
  console.log('');
  console.log('Seterusnya: node adb-mock.js devices');
}

main();
