const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { exec, execFile } = require('child_process');
const crypto = require('crypto');
const { createAvasManager } = require('./avas-manager');
const { streamBackup } = require('./backup-transfer');
const net = require('net');

const PORT = 5500;
let connectedIp = null;
let carModel = 'IHU801P';
const logs = [];

let currentTask = {
    active: false,
    label: '',
    progress: 0,
    detail: '',
    status: null,
    transfer: null
};

// P2 FIX: kunci single-flight sebenar. Dulu hanya state paparan (currentTask)
// yang wujud, jadi dua operasi tulis boleh berjalan serentak dan saling tindih
// (cth: backup + install mount pada masa sama).
let busyLabel = null;

function setProgress(progress, label = null, detail = null) {
    const p = Math.min(100, Math.max(0, Math.round(progress)));
    currentTask.progress = p;
    currentTask.status = null;
    currentTask.transfer = null;
    // P2 FIX: dulu currentTask.active tidak pernah dibersihkan, jadi bar boleh
    // tersangkut pada 100% + mesej lama. Sekarang 'active' dikira semula setiap kali.
    currentTask.active = p > 0 && p < 100;
    if (label !== null) currentTask.label = label;
    if (detail !== null) currentTask.detail = detail;
}

// P2 FIX: bersihkan state task supaya UI tidak mewarisi mesej task sebelumnya.
function resetTask() {
    currentTask.active = false;
    currentTask.progress = 0;
    currentTask.label = '';
    currentTask.detail = '';
    currentTask.status = null;
    currentTask.transfer = null;
}

function log(msg) {
    const timestamp = new Date().toLocaleTimeString();
    const entry = `[${timestamp}] ${msg}`;
    console.log(entry);
    logs.push(entry);
    if (logs.length > 500) logs.shift();
}

function getAdbPath() {
    const localTools = path.join(__dirname, 'tools', 'adb.exe');
    if (fs.existsSync(localTools)) return localTools;

    const scoopAdb = path.join(os.homedir(), 'scoop', 'apps', 'android-clt', 'current', 'platform-tools', 'adb.exe');
    if (fs.existsSync(scoopAdb)) return scoopAdb;

    return 'adb';
}

function getFfmpegPath() {
    const localTools = path.join(__dirname, 'tools', 'ffmpeg.exe');
    if (fs.existsSync(localTools)) return localTools;
    return 'ffmpeg';
}

const ADB = getAdbPath();
const FFMPEG = getFfmpegPath();
log(`ADB binary available at: ${ADB}`);
log(`FFmpeg binary available at: ${FFMPEG}`);

function runAdb(args, timeoutMs = 25000) {
    return new Promise((resolve) => {
        exec(`"${ADB}" ${args}`, { timeout: timeoutMs, encoding: 'utf8', windowsHide: true }, (err, stdout, stderr) => {
            resolve({
                success: !err,
                stdout: (stdout || '').trim(),
                stderr: (stderr || '').trim(),
                exitCode: err ? (err.code || 1) : 0
            });
        });
    });
}

function runShell(ip, cmd, timeoutMs = 25000) {
    return runAdb(`-s ${ip}:5555 shell "${cmd.replace(/"/g, '\\"')}"`, timeoutMs);
}

async function partitionSize(ip, name) {
    const size = await runShell(ip, `blockdev --getsize64 /dev/block/by-name/${name}`);
    const bytes = Number(size.stdout);
    if (size.success && /^\d+$/.test(size.stdout) && Number.isSafeInteger(bytes) && bytes > 0) return bytes;

    const resolved = await runShell(ip, `readlink -f /dev/block/by-name/${name}`);
    const device = path.posix.basename(resolved.stdout);
    if (!resolved.success || !/^[a-zA-Z0-9_-]+$/.test(device)) {
        throw new Error(`Cannot resolve the ${name} block device to determine its size.`);
    }
    const sizeInSectors = await runShell(ip, `cat /sys/class/block/${device}/size`);
    const fallbackBytes = Number(sizeInSectors.stdout) * 512;
    if (!sizeInSectors.success || !/^\d+$/.test(sizeInSectors.stdout) || !Number.isSafeInteger(fallbackBytes) || fallbackBytes <= 0) {
        throw new Error(`Cannot verify the size of partition ${name}; no image transfer was started.`);
    }
    return fallbackBytes;
}

async function backupPartitions(ip, directory, names, label, detail) {
    currentTask = {
        active: true, label, progress: null, detail, status: 'running',
        transfer: {
            bytesTransferred: 0, totalBytes: null, bytesPerSecond: null,
            etaSeconds: null, currentFile: null, fileIndex: 0, fileCount: names.length
        }
    };
    log(`${label}: ${detail} Destination: ${directory}`);
    try {
        if (!ip) throw new Error('Car is not connected.');
        await fs.promises.mkdir(directory, { recursive: true });
        const sizes = [];
        for (const name of names) sizes.push(await partitionSize(ip, name));
        const totalBytes = sizes.reduce((sum, size) => sum + size, 0);
        if (!Number.isSafeInteger(totalBytes)) throw new Error('Combined partition size exceeds the supported byte count.');
        currentTask.transfer.totalBytes = totalBytes;
        currentTask.progress = 0;
        let completedBytes = 0;
        for (let index = 0; index < names.length; index++) {
            const name = names[index];
            const currentFile = `${name}.img`;
            currentTask.transfer.currentFile = currentFile;
            currentTask.transfer.fileIndex = index + 1;
            currentTask.transfer.bytesPerSecond = null;
            currentTask.transfer.etaSeconds = null;
            currentTask.detail = `[${index + 1}/${names.length}] Streaming ${currentFile} directly to this PC. ${detail}`;
            log(`[${index + 1}/${names.length}] Streaming ${currentFile} (${sizes[index]} bytes), without staging an image on the IHU.`);
            // Suppress dd diagnostics on the binary channel, then append its actual
            // exit status. The transfer helper strips and verifies this trailer.
            const remote = `dd if=/dev/block/by-name/${name} bs=4M 2>/dev/null; result=$?; printf "\\nEMAS_BACKUP_EXIT:%s\\n" "$result"`;
            await streamBackup({
                command: ADB,
                args: ['-s', `${ip}:5555`, 'exec-out', 'sh', '-c', remote],
                destination: path.join(directory, currentFile),
                expectedBytes: sizes[index],
                onProgress: sample => {
                    const bytesTransferred = completedBytes + sample.bytesTransferred;
                    const bytesPerSecond = sample.bytesPerSecond;
                    Object.assign(currentTask.transfer, {
                        bytesTransferred, bytesPerSecond,
                        etaSeconds: bytesPerSecond > 0 ? (totalBytes - bytesTransferred) / bytesPerSecond : null
                    });
                    // Full byte receipt is not success until exit status and disk
                    // verification pass. Show an indeterminate verification phase.
                    currentTask.progress = bytesTransferred < totalBytes ? bytesTransferred * 100 / totalBytes : null;
                    if (bytesTransferred === totalBytes) {
                        currentTask.detail = `[${index + 1}/${names.length}] Verifying ${currentFile}: all bytes received. ${detail}`;
                    }
                }
            });
            completedBytes += sizes[index];
            log(`Verified ${currentFile}: ${sizes[index]} bytes saved.`);
        }
        currentTask.active = false;
        currentTask.status = 'completed';
        currentTask.progress = 100;
        currentTask.transfer.bytesPerSecond = 0;
        currentTask.transfer.etaSeconds = 0;
        currentTask.detail = `Verified images saved in ${directory}. ${detail}`;
        log(`${label} completed. ${currentTask.detail}`);
        return { success: true, message: currentTask.detail };
    } catch (error) {
        currentTask.active = false;
        currentTask.status = 'failed';
        currentTask.transfer.bytesPerSecond = 0;
        currentTask.transfer.etaSeconds = null;
        currentTask.detail = `Backup failed: ${error.message} Any incomplete .partial image is retained; remaining partitions were not started.`;
        log(currentTask.detail);
        return { success: false, message: currentTask.detail };
    }
}

// ============================================================================
// GUARD HELPERS
// Peraturan besi: JANGAN buang / ganti apa-apa fail atas kereta sebelum
// pengganti sudah DISAHKAN wujud dan saiznya padan. Umount slot AVAS tanpa
// backup yang sah = slot SENYAP sepenuhnya.
// ============================================================================

// Saiz fail atas kereta dalam bait. -1 = tiada / bukan fail biasa.
async function remoteFileSize(ip, remotePath, timeoutMs = 8000) {
    if (!remotePath) return -1;
    const res = await runShell(ip, `stat -c %s "${remotePath}"`, timeoutMs);
    const out = (res.stdout || '').trim();
    if (!out) return -1;
    const last = out.split('\n').pop().trim();
    if (!/^\d+$/.test(last)) return -1;
    const n = parseInt(last, 10);
    return Number.isFinite(n) ? n : -1;
}

// Baca /proc/mounts sekali, pulangkan struktur. Elak `mount | grep "..."`:
// pipe + petikan dalaman pecah melalui cmd.exe pada Windows (disahkan 1/91 rosak).
async function getMountLines(ip) {
    const res = await runShell(ip, 'cat /proc/mounts');
    return (res.stdout || '')
        .split('\n')
        .map((line) => {
            const c = line.split(' ');
            return c.length > 2 ? { source: c[0], target: c[1], fstype: c[2], opts: c[3] || '' } : null;
        })
        .filter(Boolean);
}

// Adakah path ini benar-benar target satu bind mount yang aktif sekarang?
async function isBindMounted(ip, targetPath) {
    if (!targetPath) return false;
    const mounts = await getMountLines(ip);
    return mounts.some((m) => m.target === targetPath);
}

// Cari laluan slot sebenar + fail APK di dalamnya + status ditimpa.
async function resolveSlotTarget(ip, sys) {
    let target = null;
    let targetIsFile = false;

    for (const p of sys.paths) {
        const dirCheck = await runShell(ip, `[ -d "${p}" ] && echo DIR`);
        if ((dirCheck.stdout || '').includes('DIR')) { target = p; targetIsFile = false; break; }
        const fileCheck = await runShell(ip, `[ -f "${p}" ] && echo FILE`);
        if ((fileCheck.stdout || '').includes('FILE')) { target = p; targetIsFile = true; break; }
    }

    if (!target) {
        return {
            targetPath: null, apkPath: null, bindTarget: null,
            isOverwritten: false, overwrittenBy: 'None', missing: true
        };
    }

    // Nama APK dalam slot biasanya SAMA dengan nama folder (cth: JOOXMusic_ACO/JOOXMusic_ACO.apk).
    let apkPath = target;
    if (!targetIsFile) {
        const lsRes = await runShell(ip, `ls -1 "${target}"`);
        const entries = (lsRes.stdout || '').split('\n').map((s) => s.trim()).filter(Boolean);
        const dirBase = target.split('/').pop();
        const sameName = entries.find((e) => e === `${dirBase}.apk`);
        const anyApk = entries.find((e) => e.toLowerCase().endsWith('.apk'));
        const inner = sameName || anyApk || null;
        apkPath = inner ? `${target}/${inner}` : null;
    }

    // Bukti "ditimpa": bind mount AKTIF pada apk atau folder slot ini.
    const mounts = await getMountLines(ip);
    const hit = mounts.find((m) => (apkPath && m.target === apkPath) || m.target === target);

    let isOverwritten = !!hit;
    let overwrittenBy = 'None';

    if (hit) {
        const mo = hit.source.match(/\/custom_apps\/([^/\s]+)/);
        overwrittenBy = mo ? mo[1] : (hit.source.split('/').pop() || 'Custom');
    } else if (targetIsFile) {
        // Kes in-place replace (cth: Noor.apk diganti terus tanpa bind mount).
        const sz = await remoteFileSize(ip, target);
        if (sz > 3500000) { isOverwritten = true; overwrittenBy = 'In-place replace'; }
    }

    return {
        targetPath: target,
        apkPath,
        bindTarget: apkPath || target,
        isOverwritten,
        overwrittenBy,
        missing: false
    };
}

// P1 FIX: tulis ketahanan boot SEBENAR.
// `run_binds.sh` lama TIDAK dipanggil oleh sesiapa, jadi setiap reboot hilang
// semua mount. Di sini kita jana fail init .rc dari mount yang MEMANG aktif,
// tulis ke /system/etc/init/, dan sahkan kandungannya selepas tulis.
async function writeBootPersistence(ip) {
    const RC_PATH = '/system/etc/init/zzz_emastool_mounts.rc';
    const TMP_RC = '/data/local/tmp/zzz_emastool_mounts.rc';
    const LEGACY_RC = '/system/etc/init/zzz_custom_apps.rc';

    // Jana senarai dari mount SEBENAR yang aktif sekarang (bukan dari hafalan).
    const mounts = await getMountLines(ip);
    const custom = mounts.filter((m) => m.source.startsWith('/data/local/custom_apps/'));

    if (custom.length === 0) {
        return { ok: false, message: 'No active custom_apps bind mounts to record.' };
    }

    const body = [
        '# Dijana oleh e.MAS 5 Desktop Control Center â€” JANGAN edit manual.',
        '# Ditulis dari mount SEBENAR yang aktif; reboot akan memulihkannya.',
        'on post-fs-data'
    ];
    for (const m of custom) body.push(`    mount none ${m.source} ${m.target} bind rec`);
    const content = body.join('\n') + '\n';

    // Simpan salinan .rc lama ke laptop SEBELUM menulis apa-apa.
    const bakDir = path.join(__dirname, 'temp_audio');
    if (!fs.existsSync(bakDir)) fs.mkdirSync(bakDir, { recursive: true });
    await runAdb(`-s ${ip}:5555 pull "${RC_PATH}" "${path.join(bakDir, 'zzz_emastool_mounts.rc.bak')}"`, 15000);

    const tmpLocal = path.join(bakDir, `zzz_emastool_mounts_${Date.now()}.rc`);
    fs.writeFileSync(tmpLocal, content, 'latin1');
    const pushRes = await runAdb(`-s ${ip}:5555 push "${tmpLocal}" "${TMP_RC}"`, 20000);
    try { fs.unlinkSync(tmpLocal); } catch (e) { /* abaikan */ }

    if (pushRes.exitCode !== 0) {
        return { ok: false, message: 'Failed to send the .rc file to the car.' };
    }

    // /system bukan mount point berasingan pada IHU ini â€” ia atas '/'.
    await runShell(ip, 'mount -o rw,remount /');
    await runShell(ip, `cp "${TMP_RC}" "${RC_PATH}"`);
    await runShell(ip, `chmod 644 "${RC_PATH}"`);
    await runShell(ip, `chown root:root "${RC_PATH}"`);
    await runShell(ip, `chcon u:object_r:system_file:s0 "${RC_PATH}"`);
    await runShell(ip, 'mount -o ro,remount /');
    await runShell(ip, `rm -f "${TMP_RC}"`);

    // Sahkan kandungan benar-benar bertulis sebelum mengaku berjaya.
    const backRes = await runShell(ip, `cat "${RC_PATH}"`);
    const expected = custom.map((m) => `mount none ${m.source} ${m.target} bind rec`);
    const missing = expected.filter((e) => !(backRes.stdout || '').includes(e));
    if (missing.length > 0) {
        return { ok: false, message: `Incomplete .rc file (${missing.length} lines missing); mounts will be lost after reboot.` };
    }

    // Beritahu kalau ada .rc lama yang turut memasang mount (boleh bertindih).
    const legacy = await runShell(ip, `[ -f "${LEGACY_RC}" ] && echo ADA`);
    const legacyNote = (legacy.stdout || '').includes('ADA')
        ? ' Note: the legacy zzz_custom_apps.rc still exists and also installs mounts. Duplicate mounts are not harmful, but you can request cleanup.'
        : '';

    return { ok: true, message: `${expected.length} mounts recorded in ${RC_PATH}.${legacyNote}` };
}

// Subnet Scanning on port 5555
async function scanSubnets() {
    const ipsToScan = new Set();
    const interfaces = os.networkInterfaces();

    for (const name of Object.keys(interfaces)) {
        for (const netInfo of interfaces[name]) {
            if (netInfo.family === 'IPv4' && !netInfo.internal) {
                const parts = netInfo.address.split('.');
                const base = `${parts[0]}.${parts[1]}.${parts[2]}`;
                for (let i = 1; i <= 254; i++) {
                    ipsToScan.add(`${base}.${i}`);
                }
            }
        }
    }

    // Always include hotspot defaults
    for (let i = 1; i <= 254; i++) {
        ipsToScan.add(`192.168.43.${i}`);
    }

    log(`Scanning port 5555 across ${ipsToScan.size} IP addresses...`);
    const foundIps = [];

    const probeIp = (ip) => {
        return new Promise((resolve) => {
            const socket = new net.Socket();
            socket.setTimeout(650);

            socket.on('connect', () => {
                socket.destroy();
                resolve(ip);
            });

            socket.on('timeout', () => {
                socket.destroy();
                resolve(null);
            });

            socket.on('error', () => {
                socket.destroy();
                resolve(null);
            });

            socket.connect(5555, ip);
        });
    };

    const batchSize = 120;
    const allIps = Array.from(ipsToScan);

    for (let i = 0; i < allIps.length; i += batchSize) {
        const batch = allIps.slice(i, i + batchSize);
        const results = await Promise.all(batch.map(probeIp));
        for (const res of results) {
            if (res) foundIps.push(res);
        }
    }

    log(`Port 5555 is open at: ${foundIps.join(', ') || 'none'}. Verifying the IHU model...`);

    // Sahkan IHU801P DAN pastikan peranti authorize. Port 5555 terbuka bukan
    // bermakna ia kereta — boleh jadi telefon/tablet yang belum authorize.
    const unauthorized = [];
    for (const candIp of foundIps) {
        await runAdb(`connect ${candIp}:5555`);
        const modelCheck = await runShell(candIp, 'getprop ro.product.model', 2500);
        const out = (modelCheck.stdout || '').trim();
        const errtxt = (modelCheck.stderr || '').trim();

        if (/unauthorized/i.test(out) || /unauthorized/i.test(errtxt)) {
            unauthorized.push(candIp);
            log(`SKIP ${candIp}: unauthorized device (accept the ADB authorization prompt on that device).`);
            continue;
        }

        if (out && /IHU|801|Geely/i.test(out)) {
            log(`Proton e.MAS 5 verified at: ${candIp} (${out})`);
            return [candIp];
        }

        log(`SKIP ${candIp}: model "${out || 'no response'}" is not IHU801P.`);
    }

    if (unauthorized.length > 0) {
        log(`Port 5555 is open at ${unauthorized.join(', ')} but ADB is unauthorized. No car was found.`);
    }
    // Jangan pulangkan peranti yang belum sah — pemanggil akan tersilap sambung.
    return [];
}

// ---------------------------------------------------------------------------
// Senarai putih slot — HANYA slot ini boleh ditimpa.
// Selebihnya dilindungi: komponen sistem teras atau aplikasi penting.
// ---------------------------------------------------------------------------
const SLOT_POLICY = {
    // Slot media/pihak ketiga yang memang direka untuk diganti.
    ALLOWED: ['ttincar_tiktok_ACO', 'JOOXMusic_ACO', 'AudioPlus_ACO'],
    // Slot in-place replace (APK ditulis ganti, bukan bind mount).
    // Ini disengajakan: TheNoor sudah diganti dengan EmasEnergy pada kereta ini.
    ALLOWED_INPLACE: ['Noor', 'Noor_ACO', 'TheNoor_ACO'],
    // Pola yang DILINDUNGI — menimpa ini boleh merosakkan IHU.
    PROTECTED_PATTERNS: [
        /^\/system\/priv-app\//,
        /^\/vendor\//,
        /^\/product\/app\//,
        /launcher/i,
        /settings/i,
        /systemui/i,
        /\.ecarx\./i,
        /com\.android\./i,
        /com\.google\.android\./i
    ]
};

/**
 * Tentukan sama ada slot boleh ditimpa.
 * @returns {{allowed:boolean, reason:string, severity:string}}
 */
function checkSlotPolicy(slot) {
    const p = slot.bindTarget || slot.apkPath || slot.targetPath || '';

    // Slot A-STORE (dipasang pengguna) — dibenarkan, tetapi beri amaran.
    if (slot.type === 'A-STORE') {
        return {
            allowed: true,
            severity: 'warn',
            reason: 'Third-party app (A-Store). An APK slot overlay is allowed, but the original app will disappear from the list.'
        };
    }

    // Slot SYSTEM — semak senarai putih.
    const dirName = p.split('/').filter(Boolean).slice(-1)[0] || '';
    const dirBase = dirName.replace(/\.apk$/i, '');

    if (SLOT_POLICY.ALLOWED.includes(dirBase)) {
        return {
            allowed: true,
            severity: 'ok',
            reason: `Slot ${dirBase} allows an APK overlay (media/third-party app).`
        };
    }

    // Slot in-place replace — dibenarkan tetapi dengan amaran khusus.
    if (SLOT_POLICY.ALLOWED_INPLACE.includes(dirBase)) {
        return {
            allowed: true,
            severity: 'warn',
            reason: `Slot ${dirBase} uses in-place replacement (the APK is overwritten). Replacement is allowed, but restoring the original APK requires a backup or OTA.`
        };
    }

    for (const re of SLOT_POLICY.PROTECTED_PATTERNS) {
        if (re.test(p)) {
            return {
                allowed: false,
                severity: 'block',
                reason: `PROTECTED: ${p} is a core system component. Overwriting it could damage the IHU or disable essential functions.`
            };
        }
    }

    // Tidak dikenali — tolak secara lalai (fail-safe).
    return {
        allowed: false,
        severity: 'block',
        reason: `UNKNOWN: ${p} is not on the slot allowlist. Blocked for safety.`
    };
}

// Robust Slot Scanner
async function scanCarSlots(ip) {
    log(`Scanning app slots on the car (${ip})...`);

    // P1 FIX: baca terus /proc/mounts â€” jangan bergantung pada `mount | grep` yang
    // ada pipe + petikan dalaman (pecah kat cmd.exe Windows).
    const mountLines = await getMountLines(ip);

    const slots = [];
    const seenNames = new Set();

    // P1 FIX: checkMod() lama memang tak pernah dipanggil dan tak boleh jalan
    // (slot TikTok simpan APK bernama sama macam folder, jadi glob *.apk tak nampak).
    // Sekarang: sahkan folder, senaraikan isi, dan padan nama APK dgn nama folder.
    const knownSystemDirs = [
        {
            name: 'TikTok',
            paths: ['/system/app/ttincar_tiktok_ACO', '/system/app/TikTok'],
            defaultPkg: 'com.zhiliaoapp.musically'
        },
        {
            name: 'JOOX Music',
            paths: ['/system/app/JOOXMusic_ACO', '/system/app/JOOX'],
            defaultPkg: 'com.tencent.ibg.joox'
        },
        {
            name: 'TheNoor',
            paths: ['/system/app/Noor_ACO', '/system/app/TheNoor_ACO', '/system/app/Noor'],
            defaultPkg: 'com.noor.thenoorcar'
        },
        {
            name: 'Audio+',
            paths: ['/system/app/AudioPlus_ACO', '/product/app/AudioPlus_ACO', '/system/app/AudioPlus'],
            defaultPkg: 'com.malaysia.media'
        }
    ];

    for (const sys of knownSystemDirs) {
        const found = await resolveSlotTarget(ip, sys);
        seenNames.add(sys.name);
        seenNames.add(sys.defaultPkg);
        slots.push({
            name: sys.name,
            packageName: sys.defaultPkg,
            targetPath: found.targetPath,
            apkPath: found.apkPath,
            bindTarget: found.bindTarget,
            type: 'SYSTEM',
            isOverwritten: found.isOverwritten,
            overwrittenBy: found.overwrittenBy,
            policy: checkSlotPolicy({
                type: 'SYSTEM', targetPath: found.targetPath,
                apkPath: found.apkPath, bindTarget: found.bindTarget
            })
        });
    }

    // 2. Scan 3rd-Party A-Store Slots (User installed apps)
    const thirdRes = await runShell(ip, 'pm list packages -f -3');
    const thirdLines = (thirdRes.stdout || '').split('\n').filter(Boolean);

    const friendlyNames = {
        'com.malaysia.syok': 'SYOK Radio',
        'com.thisisaim.hotfm': 'Hot FM',
        'me.zhanghai.android.files': 'Material Files',
        'com.vivaldi.browser.automotive': 'Vivaldi Browser',
        'com.cybercat.acbridge': 'AC Bridge'
    };

    for (const line of thirdLines) {
        const m = line.match(/package:(.+?)=([a-zA-Z0-9_\.]+)/);
        if (m) {
            const apkPath = m[1].trim();
            const pkg = m[2].trim();

            if (!seenNames.has(pkg) && !pkg.startsWith('org.smarttube') && !pkg.startsWith('com.netflix')) {
                seenNames.add(pkg);

                // P1 FIX: bandingkan mount target (ruang ke-2 /proc/mounts), bukan substring.
                // Mount sebenar: target = laluan slot, source = fail custom_apps.
                const hitMount = mountLines.find((l) => l.target === apkPath);
                const isOverwritten = !!hitMount;
                let overwrittenBy = 'None';
                if (hitMount) {
                    const matchOver = hitMount.source.match(/\/custom_apps\/([^/\s]+)/);
                    if (matchOver) overwrittenBy = matchOver[1];
                }

                let friendlyName = friendlyNames[pkg] || pkg.split('.').pop();
                friendlyName = friendlyName.charAt(0).toUpperCase() + friendlyName.slice(1);

                slots.push({
                    name: friendlyName,
                    packageName: pkg,
                    targetPath: apkPath,
                    apkPath: apkPath,
                    bindTarget: apkPath,
                    type: 'A-STORE',
                    isOverwritten,
                    overwrittenBy,
                    policy: checkSlotPolicy({ type: 'A-STORE', apkPath, bindTarget: apkPath })
                });
            }
        }
    }

    return slots;
}

function getPresetPath(subpath) {
    const localPreset = path.join(__dirname, 'presets', subpath);
    if (fs.existsSync(localPreset)) return localPreset;

    // Fallback: pakej unlock (dinamakan semula daripada PAKEJ_KAWAN_EMAS5).
    const repoPreset = path.join(__dirname, '..', 'PAKEJ_UNLOCK_ADB_EMAS5', '2_PASANG_SEMUA', subpath);
    if (fs.existsSync(repoPreset)) return repoPreset;

    return null;
}

const avasManager = createAvasManager({
    adb: (args, timeout = 25000) => new Promise(resolve => {
        execFile(ADB, args, { timeout, encoding: 'utf8', windowsHide: true, maxBuffer: 1024 * 1024 }, (err, stdout, stderr) => {
            resolve({ success: !err, stdout: (stdout || '').trim(), stderr: (stderr || '').trim() });
        });
    }),
    presetPath: getPresetPath,
    tempDir: path.join(__dirname, 'temp_audio')
});
const MUTATING = ['/api/install-slot', '/api/install-preset', '/api/upload-convert-audio', '/api/connect', '/api/scan'];

// HTTP Server
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const pathname = url.pathname;

    // sendJson dimiliki oleh SETIAP request; hanya request yang memegang kunci
    // boleh melepaskannya. (Frontend poll /api/status setiap 500ms â€” kalau tidak
    // dikunci begini, kunci akan dilepaskan serta-merta oleh poll itu.)
    let ownsLock = false;
    let executionStarted = false;
    req.on('end', () => { executionStarted = true; });
    req.on('aborted', () => { if (ownsLock && !executionStarted) { busyLabel = null; ownsLock = false; } });

    const sendJson = (data, code = 200) => {
        if (ownsLock && ['/api/install-preset', '/api/install-slot', '/api/upload-convert-audio'].includes(pathname)
            && typeof data.success === 'boolean' && !currentTask.transfer) {
            currentTask.active = false;
            currentTask.status = data.success ? 'completed' : 'failed';
            currentTask.progress = data.success ? 100 : null;
            currentTask.label = data.success ? (currentTask.label || 'Operation complete') : 'Operation failed';
            currentTask.detail = data.message || '';
        }
        if (ownsLock) { busyLabel = null; ownsLock = false; }
        res.writeHead(code, {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': 'http://127.0.0.1:5500'
        });
        res.end(JSON.stringify(data));
    };

    if (req.method === 'OPTIONS') {
        res.writeHead(200, {
            'Access-Control-Allow-Origin': 'http://127.0.0.1:5500',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, X-Audio-Type, X-File-Name, X-Slot-Num'
        });
        res.end();
        return;
    }

    // P2 FIX: semua operasi tulis berintrusif disatukan di sini supaya boleh
    // disekat semasa satu operasi lain masih berjalan (dulu dua operasi
    // serentak boleh saling tindih dan merosakkan state mount).
    // P2 FIX: single-flight â€” hanya SATU operasi tulis pada satu masa.
    if (MUTATING.includes(pathname) && (req.method === 'POST' || pathname === '/api/scan')) {
        if (busyLabel) {
            sendJson({
                success: false,
                message: `Another operation is still running (${busyLabel}). Wait for it to finish.`
            }, 409);
            return;
        }
        busyLabel = pathname;
        ownsLock = true;
        resetTask();
    }

    if (pathname === '/api/status') {
        sendJson({
            connected: !!connectedIp,
            ip: connectedIp,
            model: carModel,
            task: currentTask,
            logs
        });
        return;
    }

    if (pathname === '/api/scan') {
        log('Scanning subnets for the Proton e.MAS 5 IHU...');
        const found = await scanSubnets();
        if (found.length > 0) {
            connectedIp = found[0];
            log(`Car detected at: ${connectedIp}!`);
            await runAdb(`connect ${connectedIp}:5555`);
            const modelRes = await runShell(connectedIp, 'getprop ro.product.model');
            carModel = modelRes.stdout || 'IHU801P';
            sendJson({ success: true, ip: connectedIp, model: carModel });
        } else {
            sendJson({ success: false, message: 'No car detected on the current subnet.' });
        }
        return;
    }

    if (pathname === '/api/connect' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const { ip } = JSON.parse(body);
                log(`Connecting manually to ${ip}:5555...`);
                const connRes = await runAdb(`connect ${ip}:5555`);
                if (connRes.stdout.includes('connected')) {
                    const modelRes = await runShell(ip, 'getprop ro.product.model', 3000);
                    const out = (modelRes.stdout || '').trim();
                    const errtxt = (modelRes.stderr || '').trim();

                    if (/unauthorized/i.test(out) || /unauthorized/i.test(errtxt)) {
                        const msg = `${ip} is unauthorized. Accept the "Allow USB debugging" prompt on that device, or check the IP address.`;
                        log(msg);
                        return sendJson({ success: false, message: msg });
                    }
                    if (!/IHU|801|Geely/i.test(out)) {
                        const msg = `${ip} is not IHU801P (model: "${out || 'no response'}"). Connection rejected.`;
                        log(msg);
                        return sendJson({ success: false, message: msg });
                    }

                    connectedIp = ip;
                    carModel = out;
                    log(`Connected to ${carModel} (${connectedIp})!`);
                    sendJson({ success: true, ip: connectedIp, model: carModel });
                } else {
                    sendJson({ success: false, message: connRes.stderr || connRes.stdout });
                }
            } catch (e) {
                sendJson({ success: false, message: e.message }, 500);
            }
        });
        return;
    }

    if (pathname === '/api/slots') {
        if (!connectedIp) {
            sendJson({ success: false, message: 'Car is not connected.' }, 400);
            return;
        }
        const slots = await scanCarSlots(connectedIp);
        sendJson({ success: true, slots });
        return;
    }

    if (pathname === '/api/install-slot' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const { targetSlot, localApkPath, customAppName } = JSON.parse(body);
                if (!connectedIp) return sendJson({ success: false, message: 'Car is not connected.' });
                if (!fs.existsSync(localApkPath)) return sendJson({ success: false, message: 'Local APK file not found.' });

                // Kuatkuasa senarai putih slot. Tolak jika dilindungi.
                const pol = targetSlot.policy || checkSlotPolicy(targetSlot);
                if (!pol.allowed) {
                    const msg = `REJECTED: ${pol.reason}`;
                    log(msg);
                    setProgress(100, 'Rejected', pol.reason);
                    return sendJson({ success: false, message: msg, policy: pol }, 403);
                }
                if (pol.severity === 'warn') {
                    log(`WARNING: ${pol.reason}`);
                }

                const safeName = (customAppName || 'CustomApp').replace(/[^a-zA-Z0-9_]/g, '');
                const remoteDir = `/data/local/custom_apps/${safeName}`;
                const remoteApk = `${remoteDir}/${safeName}.apk`;

                setProgress(10, `Installing overlay: ${customAppName}`, 'Creating a folder on the car...');
                log(`[1/6] Creating folder: ${remoteDir}...`);
                await runShell(connectedIp, `mkdir -p ${remoteDir}`);

                setProgress(30, `Transferring ${customAppName}`, `Sending ${path.basename(localApkPath)} over Wi-Fi ADB...`);
                log(`[2/6] Transferring APK to the car (${path.basename(localApkPath)})...`);
                const pushRes = await runAdb(`-s ${connectedIp}:5555 push "${localApkPath}" "${remoteApk}"`, 300000);

                // P0 FIX: sahkan APK benar-benar sampai dan saiznya padan fail tempatan.
                const localApkSize = fs.statSync(localApkPath).size;
                const remoteApkSize = await remoteFileSize(connectedIp, remoteApk);
                if (pushRes.exitCode !== 0 || remoteApkSize !== localApkSize) {
                    const msg = `ABORTED: APK transfer is incomplete (local=${localApkSize} bytes, car=${remoteApkSize} bytes). The slot was NOT touched.`;
                    log(msg);
                    setProgress(100, 'Error', msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                setProgress(60, `Configuring ${customAppName}`, 'Setting permissions and SELinux context...');
                log('[3/6] Configuring file permissions and SELinux context...');
                const selinuxContext = targetSlot.type === 'SYSTEM' ? 'u:object_r:system_file:s0' : 'u:object_r:apk_data_file:s0';
                await runShell(connectedIp, `chmod 755 ${remoteDir} && chmod 644 ${remoteApk}`);
                await runShell(connectedIp, `chcon -R ${selinuxContext} ${remoteDir}`);

                setProgress(75, `Overlaying slot: ${targetSlot.name}`, 'Applying the APK bind mount...');
                log('[4/6] Applying the APK slot overlay with a bind mount...');

                // P1 FIX: mount pada TARGET SEBENAR yang dilaporkan scanner
                // (fail APK dalam slot), bukan pada folder slot.
                const bindTarget = targetSlot.bindTarget || targetSlot.apkPath || targetSlot.targetPath;
                const source = remoteApk;
                await runShell(connectedIp, `umount -l "${bindTarget}" 2>/dev/null || true`);
                await runShell(connectedIp, `mount -o bind "${source}" "${bindTarget}"`);
                await runShell(connectedIp, `restorecon -F "${bindTarget}" 2>/dev/null || true`);

                if (!(await isBindMounted(connectedIp, bindTarget))) {
                    const msg = `FAILED: bind mount at ${bindTarget} could not be verified. Slot ${targetSlot.name} was NOT overlaid.`;
                    log(msg);
                    setProgress(100, 'Error', msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                setProgress(88, `Registering ${customAppName}`, 'Registering with Package Manager...');
                log('[5/6] Registering with Package Manager...');
                const pmRes = await runShell(connectedIp, `pm install -r -d -g "${remoteApk}"`, 120000);
                if (pmRes.exitCode !== 0) {
                    log(`pm install warning: ${pmRes.stderr || pmRes.stdout} (mount is still active; the icon may need a reboot)`);
                }

                setProgress(95, 'Boot persistence', 'Writing init .rc so mounts survive a reboot...');
                log('[6/6] Writing boot persistence (init .rc, not run_binds.sh)...');

                // P1 FIX: run_binds.sh lama TIDAK dipanggil oleh sesiapa â€” reboot hilang
                // semua mount. Sekarang jana .rc init dari mount SEBENAR yang aktif.
                const persist = await writeBootPersistence(connectedIp);

                // Refresh launcher
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');

                const warn = persist.ok ? '' : ` BOOT PERSISTENCE WARNING: ${persist.message}`;
                setProgress(100, 'Complete', `Installed ${customAppName}!${warn}`);
                log(`Installation complete! ${customAppName} is active over slot ${targetSlot.name} (mount verified).${warn}`);
                sendJson({
                    success: true,
                    message: `Installed ${customAppName} as an APK overlay over slot ${targetSlot.name} (mount verified).${warn}`
                });
            } catch (e) {
                setProgress(100, 'Error', e.message);
                sendJson({ success: false, message: e.message }, 500);
            }
        });
        return;
    }

    if (pathname === '/api/upload-apk' && req.method === 'POST') {
        const chunks = [];
        let audioBytes = 0, oversized = false;
        req.on('data', chunk => {
            if (oversized) return;
            audioBytes += chunk.length;
            if (audioBytes > 32 * 1024 * 1024) {
                oversized = true; chunks.length = 0;
                sendJson({ success: false, message: 'APK upload exceeds the 32 MB limit.' }, 413);
            } else chunks.push(chunk);
        });
        req.on('end', async () => {
            try {
                const buf = Buffer.concat(chunks);
                const originalName = path.basename(req.headers['x-file-name'] || 'uploaded.apk');
                if (!/\.(apk|apks|xapk)$/i.test(originalName)) {
                    return sendJson({ success: false, message: 'Only .apk / .apks / .xapk files are accepted.' }, 400);
                }
                if (buf.length === 0) {
                    return sendJson({ success: false, message: 'An empty file was received.' }, 400);
                }

                const dir = path.join(__dirname, 'temp_uploaded_apk');
                if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
                const dest = path.join(dir, `${Date.now()}_${originalName.replace(/[^a-zA-Z0-9_.-]/g, '_')}`);
                fs.writeFileSync(dest, buf);

                // Sahkan saiz bertulis padan dengan apa yang diterima.
                const written = fs.statSync(dest).size;
                if (written !== buf.length) {
                    try { fs.unlinkSync(dest); } catch (e) { /* abaikan */ }
                    return sendJson({ success: false, message: `Incomplete upload (${written}/${buf.length} bytes).` }, 500);
                }

                log(`APK uploaded: ${originalName} (${(written / 1024 / 1024).toFixed(1)} MB).`);
                sendJson({ success: true, path: dest, name: originalName, size: written });
            } catch (e) {
                log(`APK upload error: ${e.message}`);
                sendJson({ success: false, message: e.message }, 500);
            }
        });
        return;
    }

    if (pathname === '/api/upload-convert-audio' && req.method === 'POST') {
        const chunks = [];
        let audioBytes = 0, oversized = false;
        req.on('data', chunk => {
            if (oversized) return;
            audioBytes += chunk.length;
            if (audioBytes > 32 * 1024 * 1024) {
                oversized = true; chunks.length = 0;
                sendJson({ success: false, message: 'Audio file exceeds the 32 MB limit.' }, 413);
            } else chunks.push(chunk);
        });
        req.on('end', async () => {
            try {
                if (oversized) return;
                const totalBuf = Buffer.concat(chunks);
                const targetType = req.headers['x-audio-type'] || 'lock'; // 'lock' (48k stereo) or 'avas' (48k mono)
                if (!['lock', 'avas'].includes(targetType)) return sendJson({ success: false, message: 'Invalid audio type.' }, 400);
                const originalName = path.basename(req.headers['x-file-name'] || 'uploaded_sound.mp3');
                const slotNum = req.headers['x-slot-num'] || '1';

                log(`Receiving audio: ${originalName} (${(totalBuf.length / 1024).toFixed(1)} KB) for mode: ${targetType.toUpperCase()}...`);

                const tempDir = path.join(__dirname, 'temp_audio');
                if (!fs.existsSync(tempDir)) fs.mkdirSync(tempDir, { recursive: true });

                const inputExt = '.audio';
                const inputTemp = path.join(tempDir, `input_${crypto.randomUUID()}${inputExt}`);
                const outputWav = path.join(tempDir, `output_${targetType}_${crypto.randomUUID()}.wav`);

                fs.writeFileSync(inputTemp, totalBuf);

                // Convert with FFmpeg
                // Lock sound: 48000Hz, stereo (2 channels), 16-bit PCM
                // AVAS sound: 48000Hz, mono (1 channel), 16-bit PCM
                const channels = targetType === 'avas' ? 1 : 2;
                log(`Converting to WAV 48,000 Hz 16-bit PCM (${channels === 1 ? 'MONO' : 'STEREO'})...`);

                await new Promise((resolve, reject) => {
                    execFile(FFMPEG, ['-i', inputTemp, '-y', '-ar', '48000', '-ac', String(channels), '-c:a', 'pcm_s16le', outputWav], { timeout: 120000, windowsHide: true }, (err, stdout, stderr) => {
                        if (err) {
                            log(`FFmpeg error: ${stderr || err.message}`);
                            return reject(new Error('Failed to convert audio with FFmpeg.'));
                        }
                        resolve();
                    });
                });

                fs.unlinkSync(inputTemp); // remove temp input

                if (!fs.existsSync(outputWav)) {
                    return sendJson({ success: false, message: 'The converted WAV file was not created.' }, 500);
                }

                log(`Audio converted! File size: ${(fs.statSync(outputWav).size / 1024).toFixed(1)} KB.`);

                // Direct deploy to car if connected
                if (connectedIp) {
                    if (targetType === 'lock') {
                        log('Transferring the new vehicle lock chime...');
                        await runShell(connectedIp, 'mkdir -p /data/local/window/locksounds /data/local/tmp');
                        await runAdb(`-s ${connectedIp}:5555 push "${outputWav}" /data/local/tmp/new_lock.wav`);
                        await runShell(connectedIp, 'chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                        await runShell(connectedIp, 'cp /data/local/tmp/new_lock.wav /sdcard/V3_Vehicle_Locked_Single.wav && cp /data/local/tmp/new_lock.wav /data/media/0/V3_Vehicle_Locked_Single.wav');
                        await runShell(connectedIp, 'cp /data/local/tmp/new_lock.wav /data/local/window/locksounds/lock_active.wav 2>/dev/null || true');
                        await runShell(connectedIp, 'chmod 444 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                        await runShell(connectedIp, 'rm -f /data/local/tmp/new_lock.wav');
                        await runShell(connectedIp, 'killall -HUP ecarx_reverse_radar 2>/dev/null || pkill -f ecarx_reverse_radar 2>/dev/null || true');
                        log('Custom lock chime installed and active immediately on the car!');
                        return sendJson({ success: true, message: `Custom lock chime updated and activated on the car (${originalName})!` });
                    } else if (targetType === 'avas') {
                        const reply = await avasManager.apply(connectedIp, slotNum, outputWav, path.basename(originalName));
                        log(reply.message);
                        return sendJson(reply);

                    }
                }

                sendJson({ success: true, message: 'Audio converted, but the car is not connected.' });
            } catch (err) {
                log(`Audio processing error: ${err.message}`);
                sendJson({ success: false, message: err.message }, 500);
            }
        });
        return;
    }

    if (pathname === '/api/install-preset' && req.method === 'POST') {
        let body = '', bodyBytes = 0, oversized = false;
        req.on('data', chunk => {
            if (oversized) return;
            bodyBytes += chunk.length;
            if (bodyBytes > 65536) {
                oversized = true; body = '';
                sendJson({ success: false, message: 'Request exceeds the 64 KB limit.' }, 413);
            } else body += chunk;
        });
        req.on('end', async () => {
            if (oversized) return;
            try {
            const extra = JSON.parse(body);
            const { preset, soundChoice } = extra;
            if (!connectedIp && !['backup_identity', 'backup_all', 'backup_emmc'].includes(preset)) {
                return sendJson({ success: false, message: 'Car is not connected.' });
            }

            if (preset === 'smarttube') {
                log('Installing SmartTube APK overlay (TikTok slot)...');
                const apk = getPresetPath('apps/SmartTube.apk');
                if (!apk) return sendJson({ success: false, message: 'SmartTube.apk not found.' });

                const remoteDir = '/data/local/custom_apps/SmartTube';
                const remoteApk = `${remoteDir}/SmartTube.apk`;
                await runShell(connectedIp, `mkdir -p ${remoteDir}`);
                const pushRes = await runAdb(`-s ${connectedIp}:5555 push "${apk}" "${remoteApk}"`, 300000);

                // P0 FIX: sahkan APK sampai penuh sebelum sentuh slot.
                const localSize = fs.statSync(apk).size;
                const remoteSize = await remoteFileSize(connectedIp, remoteApk);
                if (pushRes.exitCode !== 0 || remoteSize !== localSize) {
                    const msg = `ABORTED: SmartTube.apk transfer is incomplete (local=${localSize}, car=${remoteSize}). The TikTok slot was NOT touched.`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                await runShell(connectedIp, `chmod 755 ${remoteDir} && chmod 644 ${remoteApk}`);
                await runShell(connectedIp, `chcon u:object_r:system_file:s0 "${remoteApk}"`);

                // P1 FIX: bind ke fail APK sebenar dalam slot (bukan folder slot).
                const slot = await resolveSlotTarget(connectedIp, {
                    name: 'TikTok',
                    paths: ['/system/app/ttincar_tiktok_ACO', '/system/app/TikTok'],
                    defaultPkg: 'com.zhiliaoapp.musically'
                });
                const bindTarget = slot.bindTarget || slot.targetPath;
                if (!slot.apkPath && !slot.targetPath) {
                    return sendJson({ success: false, message: 'TikTok slot not found on the car.' }, 404);
                }

                await runShell(connectedIp, `umount -l "${bindTarget}" 2>/dev/null || true`);
                await runShell(connectedIp, `mount -o bind "${remoteApk}" "${bindTarget}"`);
                await runShell(connectedIp, `restorecon -F "${bindTarget}" 2>/dev/null || true`);

                if (!(await isBindMounted(connectedIp, bindTarget))) {
                    const msg = `FAILED: SmartTube mount at ${bindTarget} could not be verified.`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                const persist = await writeBootPersistence(connectedIp);
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log(`SmartTube installed and verified at ${bindTarget}.`);
                return sendJson({
                    success: true,
                    message: `SmartTube installed as an APK overlay over the TikTok slot (mount verified).` +
                             (persist.ok ? '' : ` BOOT WARNING: ${persist.message}`)
                });
            }

            if (preset === 'netflix') {
                log('Installing Netflix APK overlay (JOOX slot)...');
                const apk = getPresetPath('apps/Netflix.apk');
                if (!apk) return sendJson({ success: false, message: 'Netflix.apk not found.' });

                const remoteDir = '/data/local/custom_apps/Netflix';
                const remoteApk = `${remoteDir}/Netflix.apk`;
                await runShell(connectedIp, `mkdir -p ${remoteDir}`);
                const pushRes = await runAdb(`-s ${connectedIp}:5555 push "${apk}" "${remoteApk}"`, 600000);

                const localSize = fs.statSync(apk).size;
                const remoteSize = await remoteFileSize(connectedIp, remoteApk);
                if (pushRes.exitCode !== 0 || remoteSize !== localSize) {
                    const msg = `ABORTED: Netflix.apk transfer is incomplete (local=${localSize}, car=${remoteSize}). The JOOX slot was NOT touched.`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                await runShell(connectedIp, `chmod 755 ${remoteDir} && chmod 644 ${remoteApk}`);
                await runShell(connectedIp, `chcon u:object_r:system_file:s0 "${remoteApk}"`);

                const slot = await resolveSlotTarget(connectedIp, {
                    name: 'JOOX Music',
                    paths: ['/system/app/JOOXMusic_ACO', '/system/app/JOOX'],
                    defaultPkg: 'com.tencent.ibg.joox'
                });
                const bindTarget = slot.bindTarget || slot.targetPath;

                await runShell(connectedIp, `umount -l "${bindTarget}" 2>/dev/null || true`);
                await runShell(connectedIp, `mount -o bind "${remoteApk}" "${bindTarget}"`);
                await runShell(connectedIp, `restorecon -F "${bindTarget}" 2>/dev/null || true`);

                if (!(await isBindMounted(connectedIp, bindTarget))) {
                    const msg = `FAILED: Netflix mount at ${bindTarget} could not be verified.`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                const persist = await writeBootPersistence(connectedIp);
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log(`Netflix installed and verified at ${bindTarget}.`);
                return sendJson({
                    success: true,
                    message: `Netflix installed as an APK overlay over the JOOX Music slot (mount verified).` +
                             (persist.ok ? '' : ` BOOT WARNING: ${persist.message}`)
                });
            }

            if (preset === 'energy_app') {
                log('Installing EmasEnergy battery app (TheNoor slot)...');

                // P2 FIX: script ini tidak pernah wujud di kereta (hanya di laptop),
                // jadi dulu ia lapor "berjaya" walaupun tiada apa berlaku.
                const scriptSize = await remoteFileSize(connectedIp, '/data/local/custom_apps/deploy_energy_app.sh');
                if (scriptSize <= 0) {
                    const msg = 'ABORTED: /data/local/custom_apps/deploy_energy_app.sh is missing on the car. ' +
                                'Install that script first (it is not included in the toolbox bundle).';
                    log(msg);
                    return sendJson({ success: false, message: msg }, 409);
                }

                const runRes = await runShell(connectedIp, 'sh /data/local/custom_apps/deploy_energy_app.sh', 300000);
                if (runRes.exitCode !== 0) {
                    const msg = `FAILED: deploy_energy_app.sh exited with code ${runRes.exitCode}: ${runRes.stderr || runRes.stdout}`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log('EmasEnergy app installed (verified).');
                return sendJson({ success: true, message: 'EmasEnergy Battery Monitor installed in the TheNoor slot (verified).' });
            }

            if (preset === 'steering') {
                log('Installing the steering-button daemon (Drive Mode + Regen + Stealth toggle)...');
                const jar = getPresetPath('daemons/steering_mode_controller.jar');
                const sh1 = getPresetPath('daemons/service_mode.sh');
                const sh2 = getPresetPath('daemons/start_steering_daemon.sh');
                const chime = getPresetPath('audio/mode_chime.wav');

                // Sahkan jar yang dihantar ialah versi yang disahkan.
                const EXPECTED_SHA = '99441654707109d669c9ba63d3f1ec751d7b700ea724cb4165ebab3030ec253b';
                if (jar) {
                    const h = crypto.createHash('sha256').update(fs.readFileSync(jar)).digest('hex');
                    if (h !== EXPECTED_SHA) {
                        const msg = `ABORTED: local JAR hash mismatch (${h.slice(0, 12)}...). Expected ${EXPECTED_SHA.slice(0, 12)}...`;
                        log(msg);
                        return sendJson({ success: false, message: msg }, 500);
                    }
                    log('JAR verified (hash matches the version tested on the car).');
                }

                await runShell(connectedIp, 'mkdir -p /data/local/window');
                if (jar) await runAdb(`-s ${connectedIp}:5555 push "${jar}" /data/local/window/steering_mode_controller.jar`);
                if (sh1) await runAdb(`-s ${connectedIp}:5555 push "${sh1}" /data/local/window/service_mode.sh`);
                if (sh2) await runAdb(`-s ${connectedIp}:5555 push "${sh2}" /data/local/window/start_steering_daemon.sh`);
                if (chime) await runAdb(`-s ${connectedIp}:5555 push "${chime}" /data/local/window/mode_chime.wav`);

                await runShell(connectedIp, 'chmod 755 /data/local/window/*.sh && chmod 666 /data/local/window/*.jar');
                await runShell(connectedIp, 'chown root:root /data/local/window/steering_mode_controller.jar /data/local/window/start_steering_daemon.sh 2>/dev/null || true');
                await runShell(connectedIp, 'restorecon /data/local/window/steering_mode_controller.jar /data/local/window/start_steering_daemon.sh 2>/dev/null || true');

                // Daftar auto-start jika belum ada (supaya daemon hidup selepas reboot).
                const AVAS_BOOT = '/data/local/avas/avas_boot.sh';
                const hasHook = await runShell(connectedIp, `grep -c start_steering_daemon ${AVAS_BOOT} 2>/dev/null || echo 0`);
                if ((parseInt(hasHook.stdout.trim()) || 0) === 0) {
                    await runShell(connectedIp,
                        `printf '\\n# Steering daemon (Drive Mode + Regen + Stealth toggle)\\nif [ -f /data/local/window/start_steering_daemon.sh ]; then\\n    sh /data/local/window/start_steering_daemon.sh >> \\$LOG 2>&1\\nfi\\n' >> ${AVAS_BOOT}`);
                    log('Auto-start registered in avas_boot.sh.');
                } else {
                    log('Auto-start already exists in avas_boot.sh.');
                }

                await runShell(connectedIp, 'sh /data/local/window/start_steering_daemon.sh', 60000);
                await new Promise(res => setTimeout(res, 2000));
                const check = await runShell(connectedIp, 'pgrep -f SteeringModeDaemon');
                const ok = check.stdout.trim().length > 0;

                log(ok ? 'Steering-button daemon is active and running.' : 'WARNING: daemon was not detected after startup.');
                return sendJson({
                    success: ok,
                    message: ok
                        ? 'Steering-button daemon installed and running on the IHU. Hold Right 800 ms = Drive Mode, Left 800 ms = Regen, Down 5 s = Stealth toggle; no PC required after installation.'
                        : 'Files installed, but the daemon did not start. Check steering.log on the car.'
                });
            }

            if (preset === 'locksound') {
                const choice = soundChoice || 'mario';
                log(`Installing the vehicle lock chime (${choice})...`);

                let localWav = null;
                if (choice === 'factory' || choice === 'original') {
                    // P0 FIX: sahkan sandaran kilang dulu. Kalau tiada/kosong,
                    // jangan tulis apa-apa (dulu tetap lapor "berjaya").
                    const backupSize = await remoteFileSize(connectedIp, '/data/local/window/locksounds/lock_original.wav');
                    if (backupSize <= 0) {
                        const msg = `ABORTED: /data/local/window/locksounds/lock_original.wav is MISSING or EMPTY (size=${backupSize}). ` +
                                    `The lock chime was NOT changed, to avoid losing sound.`;
                        log(msg);
                        return sendJson({ success: false, message: msg }, 409);
                    }

                    await runShell(connectedIp, 'chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    const cpRes = await runShell(connectedIp, 'cp /data/local/window/locksounds/lock_original.wav /sdcard/V3_Vehicle_Locked_Single.wav && cp /data/local/window/locksounds/lock_original.wav /data/media/0/V3_Vehicle_Locked_Single.wav');
                    await runShell(connectedIp, 'chmod 444 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');

                    // Sahkan tulisan benar-benar berlaku (saiz fail aktif == saiz sandaran).
                    const activeSize = await remoteFileSize(connectedIp, '/data/media/0/V3_Vehicle_Locked_Single.wav');
                    if (cpRes.exitCode !== 0 || activeSize !== backupSize) {
                        const msg = `FAILED: lock chime restore could not be verified (backup=${backupSize} bytes, active=${activeSize} bytes). Check manually.`;
                        log(msg);
                        return sendJson({ success: false, message: msg }, 500);
                    }

                    await runShell(connectedIp, 'killall -HUP ecarx_reverse_radar 2>/dev/null || pkill -f ecarx_reverse_radar 2>/dev/null || true');
                    log(`Factory lock chime restored and verified (${activeSize} bytes).`);
                    return sendJson({ success: true, message: `Vehicle lock chime restored to factory original (verified).` });
                }

                if (choice === 'custom' && extra.customAudioPath) {
                    localWav = extra.customAudioPath;
                } else {
                    const soundFile = choice === 'mario' ? 'lock_mario.wav' : (choice === 'animewow' ? 'lock_animewow.wav' : 'lock_original.wav');
                    localWav = getPresetPath(`locksound/${soundFile}`);
                }

                if (localWav && fs.existsSync(localWav)) {
                    await runShell(connectedIp, 'mkdir -p /data/local/window/locksounds /data/local/tmp');
                    await runAdb(`-s ${connectedIp}:5555 push "${localWav}" /data/local/tmp/new_lock.wav`);

                    // P0 FIX: sahkan fail baru sampai sebelum tulis ganti.
                    const pushedSize = await remoteFileSize(connectedIp, '/data/local/tmp/new_lock.wav');
                    const localSize = fs.statSync(localWav).size;
                    if (pushedSize <= 0 || pushedSize !== localSize) {
                        const msg = `ABORTED: lock chime transfer is incomplete (local=${localSize}, car=${pushedSize}). The lock chime was NOT changed.`;
                        log(msg);
                        await runShell(connectedIp, 'rm -f /data/local/tmp/new_lock.wav');
                        return sendJson({ success: false, message: msg }, 500);
                    }

                    await runShell(connectedIp, 'chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    const cpRes = await runShell(connectedIp, 'cp /data/local/tmp/new_lock.wav /sdcard/V3_Vehicle_Locked_Single.wav && cp /data/local/tmp/new_lock.wav /data/media/0/V3_Vehicle_Locked_Single.wav');
                    await runShell(connectedIp, 'cp /data/local/tmp/new_lock.wav /data/local/window/locksounds/lock_active.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'chmod 444 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'rm -f /data/local/tmp/new_lock.wav');

                    const activeSize = await remoteFileSize(connectedIp, '/data/media/0/V3_Vehicle_Locked_Single.wav');
                    if (cpRes.exitCode !== 0 || activeSize !== localSize) {
                        const msg = `FAILED: new lock chime could not be verified (expected=${localSize}, active=${activeSize}). Check manually.`;
                        log(msg);
                        return sendJson({ success: false, message: msg }, 500);
                    }

                    await runShell(connectedIp, 'killall -HUP ecarx_reverse_radar 2>/dev/null || pkill -f ecarx_reverse_radar 2>/dev/null || true');
                    log(`Lock chime (${choice}) activated and verified (${activeSize} bytes).`);
                    return sendJson({ success: true, message: `Vehicle lock chime changed to: ${choice.toUpperCase()} (verified)!` });
                } else {
                    return sendJson({ success: false, message: 'Lock chime file not found.' }, 404);
                }
            }

            if (preset === 'avas') {
                const reply = await avasManager.preset(connectedIp, extra.slotNum, soundChoice, extra.customAudioPath);
                log(reply.message);
                return sendJson(reply);
            }

            if (preset === 'restore_stock') {
                // Guna service_mode.sh yang dipasang oleh PAKEJ_STEALTH_EMAS5.
                // Ini versi yang lebih lengkap: lumpuhkan apps, AVAS kilang,
                // hentikan daemon, dan BERTAHAN selepas reboot.
                log('Checking the on-car service-mode script for the PC-triggered Stealth action...');
                const hasScript = (await runShell(connectedIp, 'ls /data/local/window/service_mode.sh 2>/dev/null')).stdout.trim();
                if (!hasScript) {
                    const msg = 'service_mode.sh not found. Install PAKEJ_STEALTH_EMAS5 first; service mode is not available on this car yet.';
                    log(msg);
                    return sendJson({ success: false, message: msg }, 400);
                }

                log('Enabling Stealth Mode from this PC: hiding apps, restoring factory AVAS, stopping custom daemons...');
                setProgress(20, 'Enabling Stealth from PC', 'Running service_mode.sh on...');
                const on = await runShell(connectedIp, 'sh /data/local/window/service_mode.sh on', 120000);
                for (const line of on.stdout.split('\n').filter(Boolean)) log('  ' + line);

                setProgress(70, 'Verifying', 'Checking the final state...');
                const st = await runShell(connectedIp, 'sh /data/local/window/service_mode.sh status');
                const disabled = await runShell(connectedIp, 'pm list packages -d | wc -l');
                const mounts = await runShell(connectedIp, 'mount | grep -cE "ttincar|JOOX"');

                const isStealth = st.stdout.includes('SERVICE_MODE_ACTIVE');
                const nDisabled = parseInt(disabled.stdout.trim()) || 0;
                const nMounts = parseInt(mounts.stdout.trim()) || 0;

                setProgress(100, 'Complete', 'Service mode enabled.');
                log(`Stealth ${isStealth ? 'ACTIVE' : 'FAILED'}: ${nDisabled} packages disabled, ${nMounts} slot mounts remaining.`);

                return sendJson({
                    success: isStealth && nMounts === 0,
                    message: isStealth
                        ? `Stealth Mode ACTIVE (triggered from this PC). ${nDisabled} apps hidden, factory AVAS restored, custom daemons stopped. Survives reboot.`
                        : `FAILED: status is still ${st.stdout.trim()}. Check the log.`,
                    detail: { stealth: isStealth, disabledPackages: nDisabled, remainingMounts: nMounts }
                });
            }

            if (preset === 'stealth_off') {
                log('Disabling Stealth Mode from this PC and restoring custom features...');
                setProgress(20, 'Restoring', 'Running service_mode.sh off...');
                const off = await runShell(connectedIp, 'sh /data/local/window/service_mode.sh off', 120000);
                for (const line of off.stdout.split('\n').filter(Boolean)) log('  ' + line);

                setProgress(70, 'Verifying', 'Checking the final state...');
                const st2 = await runShell(connectedIp, 'sh /data/local/window/service_mode.sh status');
                const disabled2 = await runShell(connectedIp, 'pm list packages -d | wc -l');

                const isNormal = st2.stdout.includes('NORMAL_MODE_ACTIVE');
                const nDis2 = parseInt(disabled2.stdout.trim()) || 0;

                setProgress(100, 'Complete', 'Normal mode restored.');
                log(`Stealth OFF: status ${st2.stdout.trim()}, ${nDis2} packages still disabled.`);

                return sendJson({
                    success: isNormal,
                    message: isNormal
                        ? `Normal mode restored. Custom apps are visible again and custom AVAS is restored.`
                        : `FAILED: status is still ${st2.stdout.trim()}.`,
                    detail: { stealth: !isNormal, disabledPackages: nDis2 }
                });
            }

            if (preset === 'drivemode_install') {
                log('Starting the installed steering-button daemon (Drive Mode + Regen + Stealth toggle)...');
                const hasJar = (await runShell(connectedIp, 'ls /data/local/window/steering_mode_controller.jar 2>/dev/null')).stdout.trim();
                const hasLauncher = (await runShell(connectedIp, 'ls /data/local/window/start_steering_daemon.sh 2>/dev/null')).stdout.trim();

                if (!hasJar || !hasLauncher) {
                    const msg = 'Steering-control files are missing. Install them using PAKEJ_DRIVEMODE_EMAS5/pasang_drivemode.ps1 first.';
                    log(msg);
                    return sendJson({ success: false, message: msg }, 400);
                }

                setProgress(50, 'Starting daemon', 'Running start_steering_daemon.sh...');
                const r = await runShell(connectedIp, 'sh /data/local/window/start_steering_daemon.sh', 60000);
                for (const line of r.stdout.split('\n').filter(Boolean)) log('  ' + line);

                await new Promise(res => setTimeout(res, 2000));
                const ps = await runShell(connectedIp, 'pgrep -f SteeringModeDaemon');
                const running = ps.stdout.trim().length > 0;

                setProgress(100, 'Complete', running ? 'Steering-button daemon is running.' : 'Daemon did not start.');
                return sendJson({
                    success: running,
                    message: running
                        ? 'On-car steering-button daemon is active. Hold Right 800 ms = Drive Mode, Left 800 ms = Regen, Down 5 s = Stealth toggle; it runs without this PC.'
                        : 'Steering-button daemon did not start. Check steering.log on the car.'
                });
            }

            if (preset === 'drivemode_remove') {
                log('Stopping the steering-button daemon...');
                setProgress(50, 'Stopping', 'pkill SteeringModeDaemon...');
                await runShell(connectedIp, 'pkill -f SteeringModeDaemon 2>/dev/null || true');
                await new Promise(res => setTimeout(res, 1000));
                const ps2 = await runShell(connectedIp, 'pgrep -f SteeringModeDaemon');
                const stillRunning = ps2.stdout.trim().length > 0;

                setProgress(100, 'Complete', stillRunning ? 'Still running.' : 'Stopped.');
                return sendJson({
                    success: !stillRunning,
                    message: stillRunning
                        ? 'Daemon is still running; try a reboot.'
                        : 'Steering-button daemon stopped. Buttons return to normal volume control.'
                });
            }

            if (preset === 'stealth_status') {
                setProgress(30, 'Checking', 'Reading service-mode status...');
                const st3 = await runShell(connectedIp, 'sh /data/local/window/service_mode.sh status');
                const dis3 = await runShell(connectedIp, 'pm list packages -d | wc -l');
                const mnt3 = await runShell(connectedIp, 'mount | grep -cE "ttincar|JOOX"');
                const steer3 = await runShell(connectedIp, 'pgrep -f SteeringModeDaemon');
                const avas3 = await runShell(connectedIp, 'ls /data/local/avas/factory_backup/*.wav 2>/dev/null | wc -l');

                const state = st3.stdout.trim();
                const n3 = parseInt(dis3.stdout.trim()) || 0;
                const m3 = parseInt(mnt3.stdout.trim()) || 0;
                const s3 = steer3.stdout.trim().length > 0;
                const a3 = parseInt(avas3.stdout.trim()) || 0;

                setProgress(100, 'Complete', 'Status read.');
                log(`Status: ${state}, ${n3} disabled packages, ${m3} mounts, steering daemon ${s3 ? 'RUNNING' : 'STOPPED'}, ${a3} factory AVAS files.`);

                return sendJson({
                    success: true,
                    message: `Status: ${state.replace('_', ' ')} | ${n3} hidden apps | ${m3} slot mounts | steering daemon ${s3 ? 'active' : 'stopped'} | ${a3} factory AVAS files`,
                    detail: { state, disabledPackages: n3, remainingMounts: m3, steering: s3, avasFactoryFiles: a3 }
                });
            }

            if (preset === 'reboot') {
                log('Sending the reboot command to the car IHU...');
                await runShell(connectedIp, 'reboot');
                connectedIp = null;
                return sendJson({ success: true, message: 'IHU is rebooting...' });
            }

            if (preset === 'screenshot') {
                const filename = `IHU_Screenshot_${Date.now()}.png`;
                const dest = path.join(os.homedir(), 'Desktop', filename);
                log(`Capturing the IHU screenshot to: ${dest}...`);
                await runShell(connectedIp, 'screencap -p /data/local/tmp/screen.png');
                await runAdb(`-s ${connectedIp}:5555 pull /data/local/tmp/screen.png "${dest}"`);
                await runShell(connectedIp, 'rm -f /data/local/tmp/screen.png');
                return sendJson({ success: true, message: `Screenshot saved on the Desktop: ${filename}` });
            }

            if (preset === 'backup_identity') {
                const directory = path.join(os.homedir(), 'Desktop', 'eMAS5_Identity_Backup');
                const reply = await backupPartitions(connectedIp, directory,
                    ['proinfo', 'nvcfg', 'nvdata', 'nvram', 'protect1', 'protect2', 'seccfg', 'sec1', 'pki'],
                    'Identity and calibration backup',
                    'Read-only partition images containing device identity and calibration data.');
                return sendJson(reply, reply.success ? 200 : 500);
            }

            if (preset === 'backup_all') {
                const directory = path.join(os.homedir(), 'Desktop', 'eMAS5_Full_Backup');
                const reply = await backupPartitions(connectedIp, directory,
                    ['boot_a', 'ecarxpara', 'dtbo_a'], 'Quick partition backup',
                    'Boot, ECARX parameters and DTBO images only; not a full storage clone.');
                return sendJson(reply, reply.success ? 200 : 500);
            }

            if (preset === 'backup_emmc') {
                const directory = path.join(os.homedir(), 'Desktop', 'eMAS5_Full_eMMC_Dump');
                const reply = await backupPartitions(connectedIp, directory,
                    ['super', 'userdata'], 'Large partition dump (eMMC)',
                    'WARNING: approximately 45 GB of PC storage required (actual sizes queried before transfer). Super + userdata images only, NOT the entire raw eMMC. Live userdata is not a snapshot-consistent backup. Failed transfers retain .partial files; no resume.');
                return sendJson(reply, reply.success ? 200 : 500);
            }

            sendJson({ success: false, message: 'Invalid preset.' }, 400);
            } catch (error) {
                log(`Transaction error: ${error.message}`);
                sendJson({ success: false, message: error.message }, 500);
            }
        });
        return;
    }

    // Serve static files
    let filePath = path.join(__dirname, 'public', pathname === '/' ? 'index.html' : pathname);
    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
        const ext = path.extname(filePath).toLowerCase();
        const mimeTypes = {
            '.html': 'text/html',
            '.css': 'text/css',
            '.js': 'application/javascript',
            '.png': 'image/png',
            '.svg': 'image/svg+xml'
        };
        res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' });
        fs.createReadStream(filePath).pipe(res);
    } else {
        res.writeHead(404);
        res.end('Not Found');
    }
});

if (require.main === module) {
    server.listen(PORT, '127.0.0.1', () => {
        log(`e.MAS 5 Desktop Control Center Engine running at: http://127.0.0.1:${PORT}`);
    });
}
module.exports = { server };
