#!/usr/bin/env node
/**
 * run-sh.cjs — Jalankan skrip shell sandbox dengan laluan Android dipetakan
 * ke rootfs palsu.
 *
 * Masalah: skrip seperti boot_hook.sh menulis ke /data/local/window/... yang
 * pada Windows menjadi D:\data\local\window\... (tiada).
 *
 * Pendekatan: satu laluan regex beranchor. Pemetaan berlaku dalam SATU
 * pusingan sahaja, jadi hasil pemetaan tidak akan dipetakan semula
 * (punca laluan bercantum "rootfsD:/.../rootfs/...").
 *
 * Anchor `(^|\s|=|"|'|`|\$\(|\$\{|\()`. Tidak anchor `:` kerana laluan
 * Android sebenar tidak pernah didahului `:` — dan kita tidak mahu memetakan
 * teks dalam laluan Windows.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const BASE = __dirname;
const ROOTFS = path.join(BASE, 'rootfs');

// Semua awalan Android yang wujud. Disusun panjang -> pendek supaya
// /data/local/window menang sebelum /data/local.
const PREFIXES = [
  '/data/local/window',
  '/data/local/avas',
  '/data/local/custom_apps',
  '/data/local/init_backup',
  '/system/etc/init',
  '/system/bin',
  '/vendor/etc/avas',
  '/vendor/etc',
  '/data/local',
  '/system',
  '/vendor',
];

function toPosix(p) {
  return p.replace(/\\/g, '/');
}

/**
 * Petakan sekali sahaja.
 * Regex: (anchor)(prefix)(?![A-Za-z0-9_\-])
 *   - anchor mesti awal baris, ruang, '=', petikan, backtick, atau dalam $( )
 *   - lookahead menghalang pemadanan separa seperti /systemXYZ
 *   - ganti dengan $1 + rootfs + prefix
 */
function rewrite(src) {
  // Alternatif regex diisih panjang -> pendek; JS regex alternation
  // menggunakan yang pertama padan, jadi susunan ini penting.
  const alts = PREFIXES
    .slice()
    .sort((a, b) => b.length - a.length)
    .map((p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('|');

  const re = new RegExp(
    `(^|[\\s="'\`(]|\\$\\()(${alts})(?=[/\\s"'\`);|&]|$)`,
    'gm'
  );

  const rootPosix = toPosix(ROOTFS);

  return src.replace(re, (_m, lead, pre) => {
    return `${lead}${rootPosix}${pre}`;
  });
}

function findSh() {
  const candidates = [
    'sh',
    'C:\\Program Files\\Git\\bin\\sh.exe',
    'C:\\Program Files\\Git\\usr\\bin\\sh.exe',
    process.env.GIT_INSTALL_ROOT ? path.join(process.env.GIT_INSTALL_ROOT, 'bin', 'sh.exe') : null,
    process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Programs', 'Git', 'bin', 'sh.exe') : null,
  ].filter(Boolean);
  for (const c of candidates) {
    if (c === 'sh') {
      try {
        const { execSync } = require('child_process');
        execSync('where sh.exe', { stdio: 'ignore' });
        return 'sh';
      } catch (_) {}
    } else if (fs.existsSync(c)) {
      return c;
    }
  }
  return 'sh';
}

function main() {
  const [scriptArg, ...rest] = process.argv.slice(2);
  if (!scriptArg) {
    console.error('usage: node run-sh.cjs <script> [args...]');
    process.exit(2);
  }

  const normalized = toPosix(scriptArg);
  const real = normalized.startsWith(toPosix(ROOTFS))
    ? path.join(...normalized.split('/'))
    : path.join(ROOTFS, normalized.replace(/^\//, ''));

  if (!fs.existsSync(real)) {
    console.error(`run-sh: ${real}: not found`);
    process.exit(1);
  }

  const src = fs.readFileSync(real, 'utf8');
  const patched = rewrite(src);

  const tmp = path.join(os.tmpdir(), `sandbox_${Date.now()}_${path.basename(real)}`);
  fs.writeFileSync(tmp, patched);
  fs.chmodSync(tmp, 0o755);

  try {
    const shBin = findSh();
    const shDir = path.isAbsolute(shBin) ? path.dirname(shBin) : null;
    const env = { 
      ...process.env, 
      PATH: (shDir ? shDir + ';' : '') + path.join(BASE, 'bin') + ';' + (process.env.PATH || '') 
    };
    const out = execFileSync(shBin, [tmp, ...rest], {
      env,
      encoding: 'utf8',
      timeout: 25000,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    process.stdout.write(out);
    process.exit(0);
  } catch (e) {
    if (e.stdout) process.stdout.write(e.stdout);
    if (e.stderr) process.stderr.write(e.stderr);
    if (!e.stdout && !e.stderr && e.message) {
      console.error(`run-sh: ${e.message}`);
    }
    process.exit(e.status || 1);
  } finally {
    try { fs.unlinkSync(tmp); } catch (_) {}
  }
}

main();
