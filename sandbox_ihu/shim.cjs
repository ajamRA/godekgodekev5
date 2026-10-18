#!/usr/bin/env node
/**
 * shim.cjs — Ganti binari Android yang tiada pada Windows.
 *
 * Dipasang sebagai `pgrep`, `mount`, `pm`, `restorecon` dalam folder
 * sandbox_ihu/bin/ supaya skrip shell boleh berjalan tanpa diubah.
 *
 * Membaca state sandbox dari ../state/.
 */

const fs = require('fs');
const path = require('path');

const BASE = path.resolve(__dirname, '..');
const STATE = path.join(BASE, 'state');

const which = path.basename(process.argv[1]).replace(/\.(cmd|bat|exe)$/i, '').toLowerCase();
const args = process.argv.slice(2);

function readLines(f) {
  if (!fs.existsSync(f)) return [];
  return fs.readFileSync(f, 'utf8').split(/\r?\n/).filter(Boolean);
}
function writeLines(f, a) {
  fs.writeFileSync(f, [...new Set(a)].join('\n') + '\n');
}
const running = () => readLines(path.join(STATE, 'running'));
const setRunning = (a) => writeLines(path.join(STATE, 'running'), a);
const mounted = () => readLines(path.join(STATE, 'mounted'));
const setMounted = (a) => writeLines(path.join(STATE, 'mounted'), a);
const disabled = () => readLines(path.join(STATE, 'disabled_pkgs'));
const setDisabled = (a) => writeLines(path.join(STATE, 'disabled_pkgs'), a);

switch (which) {
  case 'pgrep': {
    const pat = args.filter(a => !a.startsWith('-')).pop() || '';
    const found = running().some(p => p.includes(pat.replace(/["']/g, '')));
    process.exit(found ? 0 : 1);
  }

  case 'pkill': {
    const pat = args.filter(a => !a.startsWith('-')).pop() || '';
    const clean = pat.replace(/["']/g, '');
    const before = running().length;
    setRunning(running().filter(p => !p.includes(clean)));
    process.exit(0);
  }

  case 'mount': {
    if (args.includes('-o') && args[1] && args[1].startsWith('remount')) {
      process.exit(0);
    }
    if (args.length === 0 || args.includes('-l')) {
      // mount output / mount | grep
      for (const m of mounted()) console.log(`/dev/block/sda1 on /${m} type ext4 (rw)`);
      process.exit(0);
    }
    // mount none SRC TGT bind rec  -> rekod
    const nonFlag = args.filter(a => !a.startsWith('-') && a !== 'none');
    if (nonFlag.length >= 2) {
      const tgt = nonFlag[nonFlag.length - 1];
      const m = mounted();
      if (!m.includes(tgt.replace(/^\//, ''))) {
        m.push(tgt.replace(/^\//, ''));
        setMounted(m);
      }
    }
    process.exit(0);
  }

  case 'umount': {
    const t = args.filter(a => !a.startsWith('-')).pop() || '';
    const clean = t.replace(/^\//, '');
    setMounted(mounted().filter(m => !m.includes(clean) && !clean.includes(m)));
    process.exit(0);
  }

  case 'pm': {
    if (args.join(' ').includes('list packages -d')) {
      for (const p of disabled()) console.log(`package:${p}`);
      process.exit(0);
    }
    if (args.includes('disable-user')) {
      const pkg = args[args.length - 1];
      const d = disabled(); if (!d.includes(pkg)) { d.push(pkg); setDisabled(d); }
      console.log(`Package ${pkg} new state: disabled-user`);
      process.exit(0);
    }
    if (args.includes('enable')) {
      const pkg = args[args.length - 1];
      setDisabled(disabled().filter(p => p !== pkg));
      console.log(`Package ${pkg} new state: enabled`);
      process.exit(0);
    }
    process.exit(0);
  }

  case 'restorecon':
  case 'chmod':
  case 'chown':
    process.exit(0);

  default:
    process.exit(0);
}
