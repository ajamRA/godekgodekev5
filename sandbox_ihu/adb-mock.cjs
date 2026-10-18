#!/usr/bin/env node
/**
 * adb-mock.js — Simulasi `adb` untuk menguji pasang_stealth.ps1 tanpa kereta.
 *
 * Arahan disokong:
 *   devices | connect <ip> | -s <dev> push <local> <remote>
 *   -s <dev> shell <cmd> | -s <dev> reboot
 *
 * Kesetiaan yang disimulasi:
 *   - /system read-only sehingga `mount -o remount,rw /`
 *   - fail .bak dalam /system/etc/init/ DIPROSES (punca masalah sebenar)
 *   - pm disable-user menyimpan senarai
 *   - daemon disimpan dalam state/running
 */

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const BASE = __dirname;
const ROOTFS = path.join(BASE, 'rootfs');
const STATE = path.join(BASE, 'state');
const REPO = path.resolve(BASE, '..');

const DEVICE = '192.168.1.20:5555';
const MODEL = 'IHU801P';
const SDK = '29';

fs.mkdirSync(STATE, { recursive: true });

const P = (remote) => path.join(ROOTFS, String(remote).replace(/^\//, ''));
const S = (name) => path.join(STATE, name);

const isRw = () => fs.existsSync(S('system_rw'));

function readLines(f) {
  if (!fs.existsSync(f)) return [];
  return fs.readFileSync(f, 'utf8').split(/\r?\n/).filter(Boolean);
}
function writeLines(f, arr) {
  fs.writeFileSync(f, arr.join('\n') + '\n');
}

const disabled = () => readLines(S('disabled_pkgs'));
const setDisabled = (a) => writeLines(S('disabled_pkgs'), [...new Set(a)].sort());
const running = () => readLines(S('running'));
const setRunning = (a) => writeLines(S('running'), a);
const mounted = () => readLines(S('mounted'));
const setMounted = (a) => writeLines(S('mounted'), a);

// ------------------------------------------------------------------ verbs
function cmdDevices() {
  console.log('List of devices attached');
  console.log(`${DEVICE}\tdevice`);
  console.log('');
}

function cmdConnect(rest) {
  console.log(`connected to ${rest[0]}`);
}

function cmdPush(rest) {
  const [local, remote] = rest;
  if (!fs.existsSync(local)) {
    console.error(`adb: error: cannot stat '${local}': No such file or directory`);
    process.exit(1);
  }
  if (String(remote).startsWith('/system/') && !isRw()) {
    console.error(`adb: error: failed to copy '${local}' to '${remote}': remote Read-only file system`);
    process.exit(1);
  }
  const dst = P(remote);
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.copyFileSync(local, dst);
  console.log(`${local}: 1 file pushed, 0 skipped.`);
}

function cmdReboot() {
  console.log('[sandbox] reboot: mensimulasi init post-fs-data');

  const initDir = P('/system/etc/init');
  let bootProcs = [];
  let bootMounts = [];

  // ---- 1. Jalankan boot_hook.sh DAHULU (post-fs-data) ----
  // Dalam init sebenar, post-fs-data berlaku sebelum boot_completed.
  const hook = P('/data/local/window/boot_hook.sh');
  if (fs.existsSync(hook)) {
    console.log('  [init] post-fs-data -> boot_hook.sh');
    try {
      const out = execFileSync('node',
        [path.join(__dirname, 'run-sh.cjs'), hook],
        { encoding: 'utf8', timeout: 20000 });
      for (const line of out.split(/\r?\n/).filter(Boolean)) {
        console.log(`  [hook] ${line}`);
      }
      // hook menulis ke log, bukan stdout; baca log untuk tentukan steering
      const hookLog = P('/data/local/window/boot_hook.log');
      if (fs.existsSync(hookLog)) {
        const lg = fs.readFileSync(hookLog, 'utf8');
        if (lg.includes('steering daemon: RUNNING') || lg.includes('Steering Daemon started')) {
          bootProcs.push('com.emas.steering.SteeringModeDaemon');
        }
        // log juga merekod skip
        if (lg.includes('STEALTH: skip semua daemon kustom')) {
          bootProcs = bootProcs.filter(p => p.includes('steering'));
        }
        if (lg.includes('STEALTH: skip semua mount')) { bootMounts = []; }
      }
      if (!out.includes('STEALTH: skip semua mount')) {
        // hook memasang mount sendiri; kita rekod dari rootfs sebenar
      }
    } catch (e) {
      console.log(`  [hook] GAGAL: ${e.message}`);
    }
  }

  // ---- 2. Proses SEMUA fail .rc (init tidak tapis sambungan) ----
  if (fs.existsSync(initDir)) {
    for (const name of fs.readdirSync(initDir).sort()) {
      const f = path.join(initDir, name);
      if (!fs.statSync(f).isFile()) continue;
      const txt = fs.readFileSync(f, 'utf8');

      // mount langsung dalam .rc
      const mountMatches = txt.match(/mount (?:none|-o bind) (\S+) (\S+)/g) || [];
      if (mountMatches.length) {
        console.log(`  [init] ${name} -> ${mountMatches.length} mount`);
        for (const m of mountMatches) {
          bootMounts.push(m.split(/\s+/).slice(-1)[0]);
        }
      }

      // exec ... start_xxx.sh
      const execMatches = txt.match(/exec(?:_background)?[^\n]*\/data\/local\/window\/(start_\w+\.sh)/g) || [];
      for (const m of execMatches) {
        const script = m.match(/start_\w+\.sh/)[0];
        console.log(`  [init] ${name} -> exec ${script}`);
        runStartScript(script, bootProcs);
      }

      // service ... pada boot_completed -> jalankan skrip sebenar
      const svc = txt.match(/service (\w+) \/system\/bin\/sh (\S+)/);
      if (svc && txt.includes('boot_completed')) {
        const target = svc[2];
        console.log(`  [init] ${name} -> service ${svc[1]} (${target})`);
        runServiceScript(target, bootProcs);
      }
    }
  }

  setRunning([...new Set(bootProcs)]);
  setMounted([...new Set(bootMounts)]);

  console.log(`  [init] daemon: ${running().length}, mount: ${mounted().length}`);
  console.log('[sandbox] boot selesai');
}

/** Jalankan skrip start_*.sh yang sebenar; daemon yang dicetak = hidup. */
function runStartScript(script, procs) {
  const p = P(`/data/local/window/${script}`);
  if (!fs.existsSync(p)) return;
  try {
    const out = execFileSync('node',
      [path.join(__dirname, 'run-sh.cjs'), p],
      { encoding: 'utf8', timeout: 15000 });
    for (const line of out.split(/\r?\n/).filter(Boolean)) {
      if (line.includes('Steering')) procs.push('com.emas.steering.SteeringModeDaemon');
      if (line.includes('Telemetry')) procs.push('com.emas.telemetry.TelemetryDaemon');
      if (line.includes('Megaphone')) procs.push('com.emas.megaphone.MegaphoneDaemon');
      if (line.includes('DoorChime')) procs.push('DoorChime');
      if (line.includes('SalamBoot')) procs.push('SalamBoot');
      if (line.includes('PressureRelief')) procs.push('com.emas.window.DoorPressureReliefDaemon');
    }
  } catch (_) {}
}

/** Jalankan skrip service; hormati semakan bendera di dalamnya. */
function runServiceScript(target, procs) {
  const p = target.startsWith('/data/local/') || target.startsWith('/system/')
    ? P(target)
    : null;
  if (!p || !fs.existsSync(p)) {
    // skrip tiada -> tiada daemon
    return;
  }
  try {
    const out = execFileSync('node',
      [path.join(__dirname, 'run-sh.cjs'), p],
      { encoding: 'utf8', timeout: 15000 });
    for (const line of out.split(/\r?\n/).filter(Boolean)) {
      if (line.includes('SKIP') || line.includes('NOT started')) {
        console.log(`  [svc] ${path.basename(p)}: ${line}`);
      }
      if (line.includes('Steering')) procs.push('com.emas.steering.SteeringModeDaemon');
      if (line.includes('Telemetry')) procs.push('com.emas.telemetry.TelemetryDaemon');
      if (line.includes('Megaphone')) procs.push('com.emas.megaphone.MegaphoneDaemon');
      if (line.includes('SalamBoot')) procs.push('SalamBoot');
    }
  } catch (_) {}
}

// ------------------------------------------------------------------ shell
function shell(cmd) {
  cmd = cmd.trim();

  // dry-run / status sesuatu skrip
  const scriptCall = cmd.match(/^sh\s+(\/\S+)\s+(--\S+)$/);
  if (scriptCall) {
    const [, s, flag] = scriptCall;
    const p = P(s);
    if (fs.existsSync(p)) {
      try {
        const out = execFileSync('node', [require('path').join(__dirname,'run-sh.cjs'), p, flag], { encoding: 'utf8', timeout: 20000 });
        process.stdout.write(out);
        return 0;
      } catch (e) {
        process.stdout.write(e.stdout || '');
        return e.status || 1;
      }
    }
  }

  // service_mode.sh
  if (cmd.includes('service_mode.sh')) {
    const p = P('/data/local/window/service_mode.sh');
    if (!fs.existsSync(p)) { console.error('sh: not found'); return 1; }
    const args = cmd.split(/\s+/).slice(2);
    try {
      const out = execFileSync('node', [require('path').join(__dirname,'run-sh.cjs'), p, ...args], { encoding: 'utf8', timeout: 20000 });
      process.stdout.write(out);
      return 0;
    } catch (e) {
      process.stdout.write(e.stdout || '');
      return e.status || 1;
    }
  }

  // mount remount
  if (cmd.includes('remount,rw')) { fs.writeFileSync(S('system_rw'), 'rw'); console.log('remount rw OK'); return 0; }
  if (cmd.includes('remount,ro')) { if (isRw()) fs.unlinkSync(S('system_rw')); console.log('remount ro OK'); return 0; }

  // getprop
  if (cmd.startsWith('getprop')) {
    const p = cmd.split(/\s+/)[1];
    if (p === 'ro.product.model') console.log(MODEL);
    else if (p === 'ro.build.version.sdk') console.log(SDK);
    else if (p === 'sys.boot_completed') console.log('1');
    return 0;
  }

  // id
  if (cmd === 'id') { console.log('uid=0(root) gid=0(root) context=u:r:su:s0'); return 0; }

  // mkdir
  if (cmd.startsWith('mkdir')) {
    for (const t of cmd.split(/\s+/).slice(2)) {
      if (t.startsWith('-')) continue;
      fs.mkdirSync(P(t), { recursive: true });
    }
    return 0;
  }

  // cp
  if (cmd.startsWith('cp ')) {
    const parts = cmd.split(/\s+/).filter(x => !x.startsWith('-'));
    const [, src, dst] = parts;
    if (!src || !dst) return 1;
    const s = P(src), d = P(dst);
    if (!fs.existsSync(s)) { console.error(`cp: ${src}: No such file`); return 1; }
    if (dst.startsWith('/system/') && !isRw()) { console.error(`cp: ${dst}: Read-only`); return 1; }
    fs.mkdirSync(path.dirname(d), { recursive: true });
    fs.copyFileSync(s, d);
    return 0;
  }

  // mv (glob di hujung)
  if (cmd.startsWith('mv ')) {
    const [, src, dst] = cmd.split(/\s+/);
    const d = P(dst);
    if (dst.startsWith('/system/') && !isRw()) { console.error(`mv: ${dst}: Read-only`); return 1; }
    fs.mkdirSync(d, { recursive: true });
    const dir = path.dirname(P(src));
    const pat = path.basename(src).replace(/\*/g, '');
    if (!fs.existsSync(dir)) return 0;
    for (const f of fs.readdirSync(dir)) {
      if (f.includes(pat)) fs.renameSync(path.join(dir, f), path.join(d, f));
    }
    return 0;
  }

  // ls (dengan glob)
  if (cmd.startsWith('ls ')) {
    const arg = cmd.split(/\s+/)[1];
    const dir = path.dirname(P(arg));
    const pat = path.basename(arg).replace(/\./g, '\\.').replace(/\*/g, '.*');
    if (!fs.existsSync(dir)) return 0;
    const re = new RegExp('^' + pat + '$');
    for (const f of fs.readdirSync(dir).sort()) {
      if (re.test(f)) console.log(String(path.join(dir, f)).replace(ROOTFS, '').replace(/\\/g, '/'));
    }
    return 0;
  }

  // if [ -f X ]; then echo YES; else echo NO; fi
  if (cmd.startsWith('if [')) {
    const m = cmd.match(/\[ -(f|d) ([^\]]+) \]/);
    if (m) {
      const exists = m[1] === 'f' ? fs.existsSync(P(m[2].trim())) && fs.statSync(P(m[2].trim())).isFile()
                                   : fs.existsSync(P(m[2].trim())) && fs.statSync(P(m[2].trim())).isDirectory();
      console.log(exists ? 'YES' : 'NO');
      return 0;
    }
    return 0;
  }

  // chmod / restorecon
  if (cmd.startsWith('chmod') || cmd.startsWith('restorecon')) return 0;

  // pm
  if (cmd.startsWith('pm ')) {
    if (cmd.includes('list packages -d')) {
      for (const p of disabled()) console.log(`package:${p}`);
      return 0;
    }
    if (cmd.includes('disable-user')) {
      const pkg = cmd.split(/\s+/).pop();
      const d = disabled(); if (!d.includes(pkg)) { d.push(pkg); setDisabled(d); }
      console.log(`Package ${pkg} new state: disabled-user`);
      return 0;
    }
    if (cmd.includes('enable')) {
      const pkg = cmd.split(/\s+/).pop();
      setDisabled(disabled().filter(p => p !== pkg));
      console.log(`Package ${pkg} new state: enabled`);
      return 0;
    }
    return 0;
  }

  // mount | grep -cE 'ttincar|JOOX'
  if (cmd.startsWith('mount')) {
    if (cmd.includes('-c')) { console.log(String(mounted().length)); return 0; }
    for (const m of mounted()) console.log(`/dev/block/sda1 on /${m} type ext4 (rw)`);
    return 0;
  }

  // ps
  if (cmd.startsWith('ps ')) {
    for (const p of running()) console.log(` 1001 1 app_process /data/local/window ${p}`);
    return 0;
  }

  // pgrep
  if (cmd.startsWith('if pgrep')) {
    const found = running().some(p => p.includes('Steering'));
    console.log(found ? 'RUNNING' : 'STOPPED');
    return 0;
  }

  // pgrep biasa (tanpa if) — pulangkan 0 jika ada, 1 jika tiada
  if (cmd.startsWith('pgrep')) {
    const found = running().some(p => p.includes('Steering'));
    return found ? 0 : 1;
  }

  // start_*.sh yang dicetak oleh skrip -> daftar daemon dalam state
  if (cmd.includes('start_steering_daemon.sh')) {
    const r = running();
    if (!r.includes('com.emas.steering.SteeringModeDaemon')) {
      r.push('com.emas.steering.SteeringModeDaemon');
      setRunning(r);
    }
    console.log('Steering Daemon started with PID 900');
    return 0;
  }

  // wc -l (kaitan dengan paip di pemasang)
  if (cmd.startsWith('wc')) { console.log('0'); return 0; }

  // netstat
  if (cmd.startsWith('netstat')) { console.log('0'); return 0; }

  return 0;
}

// ------------------------------------------------------------------ main
function main() {
  let argv = process.argv.slice(2);
  if (argv[0] === '-s') argv = argv.slice(2);
  if (argv.length === 0) { cmdDevices(); return; }

  const verb = argv[0];
  const rest = argv.slice(1);

  switch (verb) {
    case 'devices': cmdDevices(); break;
    case 'connect': cmdConnect(rest); break;
    case 'push': cmdPush(rest); break;
    case 'reboot': cmdReboot(); break;
    case 'shell': process.exit(shell(rest.join(' ')) || 0); break;
    default:
      console.error(`adb-mock: arahan tidak disokong: ${verb}`);
      process.exit(1);
  }
}

main();
