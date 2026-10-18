#!/usr/bin/env node
/**
 * run-sh.js — Jalankan skrip shell sandbox dengan laluan Android dipetakan
 * ke rootfs palsu.
 *
 * Masalah: skrip seperti boot_hook.sh menulis ke /data/local/window/... yang
 * pada Windows menjadi D:\data\local\window\... (tiada).
 *
 * Penyelesaian: baca skrip, gantikan setiap laluan mutlak Android dengan
 * laluan rootfs, tulis ke fail sementara, jalankan.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const BASE = __dirname;
const ROOTFS = path.join(BASE, 'rootfs');

// Laluan Android yang perlu dipetakan
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

function rewrite(src) {
  let out = src;
  // Susun dari paling spesifik ke paling umum, dan guna penanda sementara
  // supaya penggantian tidak berulang (cth. /data/local dipetakan dua kali).
  const ordered = [...PREFIXES].sort((a, b) => b.length - a.length);

  ordered.forEach((pre, i) => {
    const marker = `__SANDBOX_${i}__`;
    const target = toPosix(path.join(ROOTFS, pre.replace(/^\//, '')));
    out = out.split(pre).join(marker);
    out = out.split(marker).join(target);
  });
  return out;
}

function main() {
  const [scriptArg, ...rest] = process.argv.slice(2);
  if (!scriptArg) {
    console.error('usage: node run-sh.js <script> [args...]');
    process.exit(2);
  }

  const real = scriptArg.startsWith(ROOTFS)
    ? scriptArg
    : path.join(ROOTFS, scriptArg.replace(/^\//, ''));

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
    const out = execFileSync('sh', [tmp, ...rest], {
      encoding: 'utf8',
      timeout: 20000,
    });
    process.stdout.write(out);
    process.exit(0);
  } catch (e) {
    if (e.stdout) process.stdout.write(e.stdout);
    if (e.stderr) process.stderr.write(e.stderr);
    process.exit(e.status || 1);
  } finally {
    try { fs.unlinkSync(tmp); } catch (_) {}
  }
}

main();
