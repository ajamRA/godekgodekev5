const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { exec, execFile } = require('child_process');
const crypto = require('crypto');
const { createAvasManager } = require('./avas-manager');
const net = require('net');

const PORT = 5500;
let connectedIp = null;
let carModel = 'IHU801P';
const logs = [];

let currentTask = {
    active: false,
    label: '',
    progress: 0,
    detail: ''
};

// P2 FIX: kunci single-flight sebenar. Dulu hanya state paparan (currentTask)
// yang wujud, jadi dua operasi tulis boleh berjalan serentak dan saling tindih
// (cth: backup + install mount pada masa sama).
let busyLabel = null;

function setProgress(progress, label = null, detail = null) {
    const p = Math.min(100, Math.max(0, Math.round(progress)));
    currentTask.progress = p;
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
log(`ADB binary sedia ada di: ${ADB}`);
log(`FFmpeg binary sedia ada di: ${FFMPEG}`);

function runAdb(args, timeoutMs = 25000) {
    return new Promise((resolve) => {
        exec(`"${ADB}" ${args}`, { timeout: timeoutMs, encoding: 'utf8' }, (err, stdout, stderr) => {
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
            isOverwritten: false, overwrittenBy: 'Tiada', missing: true
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
    let overwrittenBy = 'Tiada';

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
        return { ok: false, message: 'tiada bind mount custom_apps aktif untuk direkod.' };
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
        return { ok: false, message: 'gagal menghantar fail .rc ke kereta.' };
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
        return { ok: false, message: `fail .rc tidak lengkap (${missing.length} baris hilang) â€” mount akan hilang selepas reboot.` };
    }

    // Beritahu kalau ada .rc lama yang turut memasang mount (boleh bertindih).
    const legacy = await runShell(ip, `[ -f "${LEGACY_RC}" ] && echo ADA`);
    const legacyNote = (legacy.stdout || '').includes('ADA')
        ? ' Nota: zzz_custom_apps.rc lama masih ada dan turut memasang mount â€” mount bertindih tidak merosakkan, tetapi beritahu saya kalau mahu ia dibersihkan.'
        : '';

    return { ok: true, message: `${expected.length} mount direkod dalam ${RC_PATH}.${legacyNote}` };
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

    log(`Memulakan imbasan port 5555 ke atas ${ipsToScan.size} alamat IP...`);
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

    log(`Port 5555 terbuka dikesan pada: ${foundIps.join(', ') || 'tiada'}. Mengesahkan model IHU...`);

    // Verify which device is IHU801P and authorized
    for (const candIp of foundIps) {
        await runAdb(`connect ${candIp}:5555`);
        const modelCheck = await runShell(candIp, 'getprop ro.product.model', 2500);
        if (modelCheck.stdout && (modelCheck.stdout.includes('IHU') || modelCheck.stdout.includes('801') || modelCheck.stdout.includes('Geely'))) {
            log(`Kereta Proton e.MAS 5 disahkan pada: ${candIp} (${modelCheck.stdout})`);
            return [candIp];
        }
    }

    return foundIps;
}

// Robust Slot Scanner
async function scanCarSlots(ip) {
    log(`Mengimbas slot aplikasi pada kereta (${ip})...`);

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
            overwrittenBy: found.overwrittenBy
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
                let overwrittenBy = 'Tiada';
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
                    overwrittenBy
                });
            }
        }
    }

    return slots;
}

function getPresetPath(subpath) {
    const localPreset = path.join(__dirname, 'presets', subpath);
    if (fs.existsSync(localPreset)) return localPreset;

    const repoPreset = path.join(__dirname, '..', 'PAKEJ_KAWAN_EMAS5', '2_PASANG_SEMUA', subpath);
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
                message: `Satu operasi lain masih berjalan (${busyLabel}). Tunggu ia selesai dahulu.`
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
        log('Memulakan imbasan subnet untuk mencari IHU Proton e.MAS 5...');
        const found = await scanSubnets();
        if (found.length > 0) {
            connectedIp = found[0];
            log(`Kereta dikesan di: ${connectedIp}!`);
            await runAdb(`connect ${connectedIp}:5555`);
            const modelRes = await runShell(connectedIp, 'getprop ro.product.model');
            carModel = modelRes.stdout || 'IHU801P';
            sendJson({ success: true, ip: connectedIp, model: carModel });
        } else {
            sendJson({ success: false, message: 'Tiada kereta dikesan dalam subnet semasa.' });
        }
        return;
    }

    if (pathname === '/api/connect' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const { ip } = JSON.parse(body);
                log(`Menyambung manual ke ${ip}:5555...`);
                const connRes = await runAdb(`connect ${ip}:5555`);
                if (connRes.stdout.includes('connected')) {
                    connectedIp = ip;
                    const modelRes = await runShell(connectedIp, 'getprop ro.product.model');
                    carModel = modelRes.stdout || 'IHU801P';
                    log(`Berjaya disambungkan ke ${carModel} (${connectedIp})!`);
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
            sendJson({ success: false, message: 'Kereta belum disambungkan.' }, 400);
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
                if (!connectedIp) return sendJson({ success: false, message: 'Kereta belum bersambung.' });
                if (!fs.existsSync(localApkPath)) return sendJson({ success: false, message: 'Fail APK tempatan tidak dijumpai.' });

                const safeName = (customAppName || 'CustomApp').replace(/[^a-zA-Z0-9_]/g, '');
                const remoteDir = `/data/local/custom_apps/${safeName}`;
                const remoteApk = `${remoteDir}/${safeName}.apk`;

                setProgress(10, `Memasang ${customAppName}`, 'Membina folder di storan kereta...');
                log(`[1/6] Membina folder: ${remoteDir}...`);
                await runShell(connectedIp, `mkdir -p ${remoteDir}`);

                setProgress(30, `Memindahkan ${customAppName}`, `Menghantar ${path.basename(localApkPath)} via Wi-Fi ADB...`);
                log(`[2/6] Memindahkan APK ke kereta (${path.basename(localApkPath)})...`);
                const pushRes = await runAdb(`-s ${connectedIp}:5555 push "${localApkPath}" "${remoteApk}"`, 300000);

                // P0 FIX: sahkan APK benar-benar sampai dan saiznya padan fail tempatan.
                const localApkSize = fs.statSync(localApkPath).size;
                const remoteApkSize = await remoteFileSize(connectedIp, remoteApk);
                if (pushRes.exitCode !== 0 || remoteApkSize !== localApkSize) {
                    const msg = `BATAL: APK tidak sampai sepenuhnya (tempatan=${localApkSize} bait, kereta=${remoteApkSize} bait). Slot TIDAK disentuh.`;
                    log(msg);
                    setProgress(100, 'Ralat', msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                setProgress(60, `Mengkonfigurasi ${customAppName}`, 'Menetapkan kebenaran & SELinux context...');
                log('[3/6] Mengkonfigurasi kebenaran fail & konteks SELinux...');
                const selinuxContext = targetSlot.type === 'SYSTEM' ? 'u:object_r:system_file:s0' : 'u:object_r:apk_data_file:s0';
                await runShell(connectedIp, `chmod 755 ${remoteDir} && chmod 644 ${remoteApk}`);
                await runShell(connectedIp, `chcon -R ${selinuxContext} ${remoteDir}`);

                setProgress(75, `Mengikat Slot ${targetSlot.name}`, 'Melaksanakan Virtual Bind-Mount...');
                log('[4/6] Melaksanakan Virtual Bind-Mount...');

                // P1 FIX: mount pada TARGET SEBENAR yang dilaporkan scanner
                // (fail APK dalam slot), bukan pada folder slot.
                const bindTarget = targetSlot.bindTarget || targetSlot.apkPath || targetSlot.targetPath;
                const source = remoteApk;
                await runShell(connectedIp, `umount -l "${bindTarget}" 2>/dev/null || true`);
                await runShell(connectedIp, `mount -o bind "${source}" "${bindTarget}"`);
                await runShell(connectedIp, `restorecon -F "${bindTarget}" 2>/dev/null || true`);

                if (!(await isBindMounted(connectedIp, bindTarget))) {
                    const msg = `GAGAL: bind mount ke ${bindTarget} tidak disahkan. Slot ${targetSlot.name} TIDAK ditimpa.`;
                    log(msg);
                    setProgress(100, 'Ralat', msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                setProgress(88, `Mendaftarkan ${customAppName}`, 'Mendaftar dengan Package Manager...');
                log('[5/6] Mendaftarkan dengan Package Manager...');
                const pmRes = await runShell(connectedIp, `pm install -r -d -g "${remoteApk}"`, 120000);
                if (pmRes.exitCode !== 0) {
                    log(`Amaran pm install: ${pmRes.stderr || pmRes.stdout} (mount masih aktif, ikon mungkin perlu reboot)`);
                }

                setProgress(95, 'Ketahanan Boot', 'Menulis init .rc supaya mount kekal selepas reboot...');
                log('[6/6] Menulis ketahanan boot (init .rc, bukan run_binds.sh)...');

                // P1 FIX: run_binds.sh lama TIDAK dipanggil oleh sesiapa â€” reboot hilang
                // semua mount. Sekarang jana .rc init dari mount SEBENAR yang aktif.
                const persist = await writeBootPersistence(connectedIp);

                // Refresh launcher
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');

                const warn = persist.ok ? '' : ` AMARAN KETAHANAN BOOT: ${persist.message}`;
                setProgress(100, 'Selesai', `Berjaya memasang ${customAppName}!${warn}`);
                log(`Pemasangan selesai! ${customAppName} aktif di atas slot ${targetSlot.name} (mount disahkan).${warn}`);
                sendJson({
                    success: true,
                    message: `Berjaya memasang ${customAppName} di atas slot ${targetSlot.name} (mount disahkan).${warn}`
                });
            } catch (e) {
                setProgress(100, 'Ralat', e.message);
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
                sendJson({ success: false, message: 'Fail audio melebihi had 32 MB.' }, 413);
            } else chunks.push(chunk);
        });
        req.on('end', async () => {
            try {
                const buf = Buffer.concat(chunks);
                const originalName = path.basename(req.headers['x-file-name'] || 'uploaded.apk');
                if (!/\.(apk|apks|xapk)$/i.test(originalName)) {
                    return sendJson({ success: false, message: 'Hanya fail .apk / .apks / .xapk diterima.' }, 400);
                }
                if (buf.length === 0) {
                    return sendJson({ success: false, message: 'Fail kosong diterima.' }, 400);
                }

                const dir = path.join(__dirname, 'temp_uploaded_apk');
                if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
                const dest = path.join(dir, `${Date.now()}_${originalName.replace(/[^a-zA-Z0-9_.-]/g, '_')}`);
                fs.writeFileSync(dest, buf);

                // Sahkan saiz bertulis padan dengan apa yang diterima.
                const written = fs.statSync(dest).size;
                if (written !== buf.length) {
                    try { fs.unlinkSync(dest); } catch (e) { /* abaikan */ }
                    return sendJson({ success: false, message: `Muat naik tidak lengkap (${written}/${buf.length} bait).` }, 500);
                }

                log(`APK dimuat naik: ${originalName} (${(written / 1024 / 1024).toFixed(1)} MB).`);
                sendJson({ success: true, path: dest, name: originalName, size: written });
            } catch (e) {
                log(`Ralat muat naik APK: ${e.message}`);
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
                sendJson({ success: false, message: 'Fail audio melebihi had 32 MB.' }, 413);
            } else chunks.push(chunk);
        });
        req.on('end', async () => {
            try {
                if (oversized) return;
                const totalBuf = Buffer.concat(chunks);
                const targetType = req.headers['x-audio-type'] || 'lock'; // 'lock' (48k stereo) or 'avas' (48k mono)
                if (!['lock', 'avas'].includes(targetType)) return sendJson({ success: false, message: 'Jenis audio tidak sah.' }, 400);
                const originalName = path.basename(req.headers['x-file-name'] || 'uploaded_sound.mp3');
                const slotNum = req.headers['x-slot-num'] || '1';

                log(`Menerima fail audio: ${originalName} (${(totalBuf.length / 1024).toFixed(1)} KB) untuk mod: ${targetType.toUpperCase()}...`);

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
                log(`Menukar format audio kepada WAV 48,000Hz 16-bit PCM (${channels === 1 ? 'MONO' : 'STEREO'})...`);

                await new Promise((resolve, reject) => {
                    execFile(FFMPEG, ['-i', inputTemp, '-y', '-ar', '48000', '-ac', String(channels), '-c:a', 'pcm_s16le', outputWav], { timeout: 120000, windowsHide: true }, (err, stdout, stderr) => {
                        if (err) {
                            log(`Ralat FFmpeg: ${stderr || err.message}`);
                            return reject(new Error('Gagal menukar format audio menggunakan FFmpeg.'));
                        }
                        resolve();
                    });
                });

                fs.unlinkSync(inputTemp); // remove temp input

                if (!fs.existsSync(outputWav)) {
                    return sendJson({ success: false, message: 'Fail WAV hasil konversi tidak terhasil.' }, 500);
                }

                log(`Audio berjaya ditukar! Saiz fail: ${(fs.statSync(outputWav).size / 1024).toFixed(1)} KB.`);

                // Direct deploy to car if connected
                if (connectedIp) {
                    if (targetType === 'lock') {
                        log('Memindahkan audio baru ke Nada Kunci Kenderaan...');
                        await runShell(connectedIp, 'mkdir -p /data/local/window/locksounds /data/local/tmp');
                        await runAdb(`-s ${connectedIp}:5555 push "${outputWav}" /data/local/tmp/new_lock.wav`);
                        await runShell(connectedIp, 'chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                        await runShell(connectedIp, 'cp /data/local/tmp/new_lock.wav /sdcard/V3_Vehicle_Locked_Single.wav && cp /data/local/tmp/new_lock.wav /data/media/0/V3_Vehicle_Locked_Single.wav');
                        await runShell(connectedIp, 'cp /data/local/tmp/new_lock.wav /data/local/window/locksounds/lock_active.wav 2>/dev/null || true');
                        await runShell(connectedIp, 'chmod 444 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                        await runShell(connectedIp, 'rm -f /data/local/tmp/new_lock.wav');
                        await runShell(connectedIp, 'killall -HUP ecarx_reverse_radar 2>/dev/null || pkill -f ecarx_reverse_radar 2>/dev/null || true');
                        log('Nada Kunci tersuai berjaya dipasang dan aktif serta-merta di kereta!');
                        return sendJson({ success: true, message: `Nada Kunci tersuai berjaya ditukar & diaktifkan ke kereta (${originalName})!` });
                    } else if (targetType === 'avas') {
                        const reply = await avasManager.apply(connectedIp, slotNum, outputWav, path.basename(originalName));
                        log(reply.message);
                        return sendJson(reply);

                    }
                }

                sendJson({ success: true, message: 'Audio berjaya ditukar tetapi kereta belum bersambung.' });
            } catch (err) {
                log(`Ralat pemprosesan audio: ${err.message}`);
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
                sendJson({ success: false, message: 'Permintaan melebihi had 64 KB.' }, 413);
            } else body += chunk;
        });
        req.on('end', async () => {
            if (oversized) return;
            try {
            const extra = JSON.parse(body);
            const { preset, soundChoice } = extra;
            if (!connectedIp) return sendJson({ success: false, message: 'Kereta belum bersambung.' });

            if (preset === 'smarttube') {
                log('Memasang SmartTube (slot TikTok)...');
                const apk = getPresetPath('apps/SmartTube.apk');
                if (!apk) return sendJson({ success: false, message: 'SmartTube.apk tidak dijumpai.' });

                const remoteDir = '/data/local/custom_apps/SmartTube';
                const remoteApk = `${remoteDir}/SmartTube.apk`;
                await runShell(connectedIp, `mkdir -p ${remoteDir}`);
                const pushRes = await runAdb(`-s ${connectedIp}:5555 push "${apk}" "${remoteApk}"`, 300000);

                // P0 FIX: sahkan APK sampai penuh sebelum sentuh slot.
                const localSize = fs.statSync(apk).size;
                const remoteSize = await remoteFileSize(connectedIp, remoteApk);
                if (pushRes.exitCode !== 0 || remoteSize !== localSize) {
                    const msg = `BATAL: SmartTube.apk tidak sampai sepenuhnya (tempatan=${localSize}, kereta=${remoteSize}). Slot TikTok TIDAK disentuh.`;
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
                    return sendJson({ success: false, message: 'Slot TikTok tidak dijumpai pada kereta.' }, 404);
                }

                await runShell(connectedIp, `umount -l "${bindTarget}" 2>/dev/null || true`);
                await runShell(connectedIp, `mount -o bind "${remoteApk}" "${bindTarget}"`);
                await runShell(connectedIp, `restorecon -F "${bindTarget}" 2>/dev/null || true`);

                if (!(await isBindMounted(connectedIp, bindTarget))) {
                    const msg = `GAGAL: mount SmartTube ke ${bindTarget} tidak disahkan.`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                const persist = await writeBootPersistence(connectedIp);
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log(`SmartTube dipasang & disahkan pada ${bindTarget}.`);
                return sendJson({
                    success: true,
                    message: `SmartTube berjaya dipasang di atas slot TikTok (mount disahkan).` +
                             (persist.ok ? '' : ` AMARAN BOOT: ${persist.message}`)
                });
            }

            if (preset === 'netflix') {
                log('Memasang Netflix (slot JOOX)...');
                const apk = getPresetPath('apps/Netflix.apk');
                if (!apk) return sendJson({ success: false, message: 'Netflix.apk tidak dijumpai.' });

                const remoteDir = '/data/local/custom_apps/Netflix';
                const remoteApk = `${remoteDir}/Netflix.apk`;
                await runShell(connectedIp, `mkdir -p ${remoteDir}`);
                const pushRes = await runAdb(`-s ${connectedIp}:5555 push "${apk}" "${remoteApk}"`, 600000);

                const localSize = fs.statSync(apk).size;
                const remoteSize = await remoteFileSize(connectedIp, remoteApk);
                if (pushRes.exitCode !== 0 || remoteSize !== localSize) {
                    const msg = `BATAL: Netflix.apk tidak sampai sepenuhnya (tempatan=${localSize}, kereta=${remoteSize}). Slot JOOX TIDAK disentuh.`;
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
                    const msg = `GAGAL: mount Netflix ke ${bindTarget} tidak disahkan.`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                const persist = await writeBootPersistence(connectedIp);
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log(`Netflix dipasang & disahkan pada ${bindTarget}.`);
                return sendJson({
                    success: true,
                    message: `Netflix berjaya dipasang di atas slot JOOX Music (mount disahkan).` +
                             (persist.ok ? '' : ` AMARAN BOOT: ${persist.message}`)
                });
            }

            if (preset === 'energy_app') {
                log('Memasang EmasEnergy App Bateri (slot TheNoor)...');

                // P2 FIX: script ini tidak pernah wujud di kereta (hanya di laptop),
                // jadi dulu ia lapor "berjaya" walaupun tiada apa berlaku.
                const scriptSize = await remoteFileSize(connectedIp, '/data/local/custom_apps/deploy_energy_app.sh');
                if (scriptSize <= 0) {
                    const msg = 'BATAL: /data/local/custom_apps/deploy_energy_app.sh tidak wujud di kereta. ' +
                                'Pasang skrip itu dahulu (ia tiada dalam bundle toolbox).';
                    log(msg);
                    return sendJson({ success: false, message: msg }, 409);
                }

                const runRes = await runShell(connectedIp, 'sh /data/local/custom_apps/deploy_energy_app.sh', 300000);
                if (runRes.exitCode !== 0) {
                    const msg = `GAGAL: deploy_energy_app.sh keluar dengan kod ${runRes.exitCode}: ${runRes.stderr || runRes.stdout}`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log('EmasEnergy App siap dipasang (disahkan).');
                return sendJson({ success: true, message: 'EmasEnergy Battery Monitor berjaya dipasang pada slot TheNoor (disahkan).' });
            }

            if (preset === 'steering') {
                log('Memasang Pengawal Butang Stereng (Hold 5 Saat)...');
                const jar = getPresetPath('daemons/steering_mode_controller.jar');
                const sh1 = getPresetPath('daemons/service_mode.sh');
                const sh2 = getPresetPath('daemons/start_steering_daemon.sh');
                const chime = getPresetPath('audio/mode_chime.wav');

                await runShell(connectedIp, 'mkdir -p /data/local/window');
                if (jar) await runAdb(`-s ${connectedIp}:5555 push "${jar}" /data/local/window/steering_mode_controller.jar`);
                if (sh1) await runAdb(`-s ${connectedIp}:5555 push "${sh1}" /data/local/window/service_mode.sh`);
                if (sh2) await runAdb(`-s ${connectedIp}:5555 push "${sh2}" /data/local/window/start_steering_daemon.sh`);
                if (chime) await runAdb(`-s ${connectedIp}:5555 push "${chime}" /data/local/window/mode_chime.wav`);

                await runShell(connectedIp, 'chmod 755 /data/local/window/*.sh && chmod 644 /data/local/window/*.jar');
                await runShell(connectedIp, 'sh /data/local/window/start_steering_daemon.sh');
                log('Mod butang stereng berjaya diaktifkan!');
                return sendJson({ success: true, message: 'Mod butang stereng siap dipasang! Hold butang Bawah 5 saat untuk toggle mod.' });
            }

            if (preset === 'locksound') {
                const choice = soundChoice || 'mario';
                log(`Memasang Nada Kunci Kenderaan (${choice})...`);

                let localWav = null;
                if (choice === 'factory' || choice === 'original') {
                    // P0 FIX: sahkan sandaran kilang dulu. Kalau tiada/kosong,
                    // jangan tulis apa-apa (dulu tetap lapor "berjaya").
                    const backupSize = await remoteFileSize(connectedIp, '/data/local/window/locksounds/lock_original.wav');
                    if (backupSize <= 0) {
                        const msg = `BATAL: /data/local/window/locksounds/lock_original.wav TIADA atau KOSONG (saiz=${backupSize}). ` +
                                    `Nada kunci TIDAK ditukar supaya tidak jadi senyap.`;
                        log(msg);
                        return sendJson({ success: false, message: msg }, 409);
                    }

                    await runShell(connectedIp, 'chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    const cpRes = await runShell(connectedIp, 'cp /data/local/window/locksounds/lock_original.wav /sdcard/V3_Vehicle_Locked_Single.wav && cp /data/local/window/locksounds/lock_original.wav /data/media/0/V3_Vehicle_Locked_Single.wav');
                    await runShell(connectedIp, 'chmod 444 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');

                    // Sahkan tulisan benar-benar berlaku (saiz fail aktif == saiz sandaran).
                    const activeSize = await remoteFileSize(connectedIp, '/data/media/0/V3_Vehicle_Locked_Single.wav');
                    if (cpRes.exitCode !== 0 || activeSize !== backupSize) {
                        const msg = `GAGAL: pulih nada kunci tidak disahkan (sandaran=${backupSize} bait, aktif=${activeSize} bait). Semak manual.`;
                        log(msg);
                        return sendJson({ success: false, message: msg }, 500);
                    }

                    await runShell(connectedIp, 'killall -HUP ecarx_reverse_radar 2>/dev/null || pkill -f ecarx_reverse_radar 2>/dev/null || true');
                    log(`Nada kunci asal kilang dipulihkan & disahkan (${activeSize} bait).`);
                    return sendJson({ success: true, message: `Nada kunci kenderaan berjaya dipulihkan kepada Original Factory (disahkan).` });
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
                        const msg = `BATAL: fail nada kunci tidak sampai sepenuhnya (tempatan=${localSize}, kereta=${pushedSize}). Nada kunci TIDAK ditukar.`;
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
                        const msg = `GAGAL: nada kunci baru tidak disahkan (sepatutnya=${localSize}, aktif=${activeSize}). Semak manual.`;
                        log(msg);
                        return sendJson({ success: false, message: msg }, 500);
                    }

                    await runShell(connectedIp, 'killall -HUP ecarx_reverse_radar 2>/dev/null || pkill -f ecarx_reverse_radar 2>/dev/null || true');
                    log(`Nada kunci (${choice}) diaktifkan & disahkan (${activeSize} bait).`);
                    return sendJson({ success: true, message: `Nada kunci kenderaan berjaya ditukar kepada mod: ${choice.toUpperCase()} (disahkan)!` });
                } else {
                    return sendJson({ success: false, message: 'Fail nada kunci tidak ditemui.' }, 404);
                }
            }

            if (preset === 'avas') {
                const reply = await avasManager.preset(connectedIp, extra.slotNum, soundChoice, extra.customAudioPath);
                log(reply.message);
                return sendJson(reply);
            }

            if (preset === 'restore_stock') {
                log('Mengaktifkan Stealth Mode: menyah-pasang semua mount custom apps...');

                // P1 FIX: umount mesti pada MOUNT TARGET, bukan folder sumber.
                // Dulu kod umount pada /system/app/JOOXMusic_ACO (folder), sedangkan
                // target sebenar ialah .../JOOXMusic_ACO/JOOXMusic_ACO.apk â€” jadi tiada
                // apa yang ter-unmount dan "Stealth Mode" tak pernah bersih.
                const liveMounts = await getMountLines(connectedIp);
                const customMounts = liveMounts.filter(
                    (m) => m.source.startsWith('/data/local/custom_apps/') || /\/system\/app\/(ttincar_tiktok_ACO|JOOXMusic_ACO|AudioPlus_ACO|Noor|Noor_ACO|TheNoor_ACO)/.test(m.target)
                );

                let unmounted = 0;
                const stillMounted = [];
                for (const m of customMounts) {
                    await runShell(connectedIp, `umount -l "${m.target}" 2>/dev/null || true`);
                    if (await isBindMounted(connectedIp, m.target)) stillMounted.push(m.target);
                    else unmounted++;
                }

                // Kes in-place replace (Noor.apk diganti terus) tak boleh di-unmount â€”
                // itu fail sebenar di sistem, kena pulih dari sandaran atau OTA.
                const inPlaceReplaced = [];
                for (const p of ['/system/app/Noor/Noor.apk', '/system/app/Noor_ACO/Noor_ACO.apk']) {
                    const sz = await remoteFileSize(connectedIp, p);
                    if (sz > 3500000) inPlaceReplaced.push(`${p} (${sz} bait)`);
                }

                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');

                if (stillMounted.length > 0) {
                    const msg = `SEBAHAGIAN GAGAL: ${unmounted} mount dibuang, tetapi ${stillMounted.length} masih aktif (${stillMounted.join(', ')}). Kereta BELUM 100% standard.`;
                    log(msg);
                    return sendJson({ success: false, message: msg }, 500);
                }

                let extra = '';
                if (inPlaceReplaced.length > 0) {
                    extra = ` PERHATIAN: ${inPlaceReplaced.length} fail diganti terus (bukan mount) dan TIDAK dipulihkan: ${inPlaceReplaced.join(', ')}. Perlu restore dari sandaran/OTA.`;
                }

                log(`Stealth Mode selesai: ${unmounted} mount dibuang.${extra}`);
                return sendJson({
                    success: true,
                    message: `Semua bind mount modifikasi telah dibuang (${unmounted} mount, disahkan).${extra}`
                });
            }

            if (preset === 'reboot') {
                log('Menghantar arahan reboot ke skrin IHU kereta...');
                await runShell(connectedIp, 'reboot');
                connectedIp = null;
                return sendJson({ success: true, message: 'IHU sedang dimulakan semula (rebooting)...' });
            }

            if (preset === 'screenshot') {
                const filename = `IHU_Screenshot_${Date.now()}.png`;
                const dest = path.join(os.homedir(), 'Desktop', filename);
                log(`Mengambil screenshot skrin IHU ke: ${dest}...`);
                await runShell(connectedIp, 'screencap -p /data/local/tmp/screen.png');
                await runAdb(`-s ${connectedIp}:5555 pull /data/local/tmp/screen.png "${dest}"`);
                await runShell(connectedIp, 'rm -f /data/local/tmp/screen.png');
                return sendJson({ success: true, message: `Screenshot disimpan di Desktop: ${filename}` });
            }

            if (preset === 'backup_identity') {
                const bDir = path.join(os.homedir(), 'Desktop', 'eMAS5_Identity_Backup');
                if (!fs.existsSync(bDir)) fs.mkdirSync(bDir, { recursive: true });
                log(`Memulakan BACKUP IDENTITI & KALIBRASI UNIK ke: ${bDir}...`);

                const identParts = [
                    { name: 'proinfo', desc: 'Hardware Serial & Device ID' },
                    { name: 'nvcfg', desc: 'Modem Radio Config' },
                    { name: 'nvdata', desc: 'Modem Calibration & IMEI' },
                    { name: 'nvram', desc: 'Wi-Fi & Bluetooth MAC' },
                    { name: 'protect1', desc: 'Radio Protection 1' },
                    { name: 'protect2', desc: 'Radio Protection 2' },
                    { name: 'seccfg', desc: 'Security Configuration' },
                    { name: 'sec1', desc: 'Security Block 1' },
                    { name: 'pki', desc: 'Vehicle PKI & Security Certificates' }
                ];

                for (let i = 0; i < identParts.length; i++) {
                    const p = identParts[i];
                    const pct = Math.round(((i + 1) / identParts.length) * 90);
                    setProgress(pct, `Menyalin ${p.name}`, `Membaca ${p.desc}...`);
                    log(`[${i + 1}/${identParts.length}] Menyalin ${p.name}.img (${p.desc})...`);
                    await runShell(connectedIp, `dd if=/dev/block/by-name/${p.name} of=/data/local/tmp/${p.name}.img bs=1M`);
                    await runAdb(`-s ${connectedIp}:5555 pull /data/local/tmp/${p.name}.img "${path.join(bDir, p.name + '.img')}"`);
                    await runShell(connectedIp, `rm -f /data/local/tmp/${p.name}.img`);
                }

                setProgress(100, 'Backup Selesai', 'Semua fail disimpan di Desktop/eMAS5_Identity_Backup.');
                log(`Backup Identiti selesai! Fail disimpan di Desktop/eMAS5_Identity_Backup.`);
                return sendJson({ success: true, message: `Sandaran Identiti Unik (~60 MB) Berjaya Disimpan di Folder Desktop/eMAS5_Identity_Backup!` });
            }

            if (preset === 'backup_all') {
                const bDir = path.join(os.homedir(), 'Desktop', 'eMAS5_Full_Backup');
                if (!fs.existsSync(bDir)) fs.mkdirSync(bDir, { recursive: true });
                log(`Memulakan BACKUP PENUH sistem kenderaan ke: ${bDir}...`);

                // 1. Boot Partition
                setProgress(5, 'Menyalin Kernel Boot', 'Membaca partition boot_a (32MB)...');
                log('[1/3] Mengambil salinan boot_a.img...');
                await runShell(connectedIp, 'dd if=/dev/block/by-name/boot_a of=/data/local/tmp/boot_a.img bs=4M');
                setProgress(15, 'Memuat Turun Boot', 'Memuat turun boot_a.img ke Laptop...');
                await runAdb(`-s ${connectedIp}:5555 pull /data/local/tmp/boot_a.img "${path.join(bDir, 'boot_a.img')}"`);
                await runShell(connectedIp, 'rm -f /data/local/tmp/boot_a.img');

                // 2. ECARX Parameter
                setProgress(25, 'Membaca ECARX Parameter', 'Menyalin ecarxpara.img (1,024MB)... Pijak brek sekali-sekala supaya IHU kekal aktif.');
                log('[2/3] Mengambil salinan ecarxpara.img...');
                await runShell(connectedIp, 'dd if=/dev/block/by-name/ecarxpara of=/data/local/tmp/ecarxpara.img bs=4M');
                setProgress(55, 'Memuat Turun ECARX Parameter', 'Memuat turun 1.0 GB ke Laptop via Wi-Fi ADB...');
                await runAdb(`-s ${connectedIp}:5555 pull /data/local/tmp/ecarxpara.img "${path.join(bDir, 'ecarxpara.img')}"`);
                await runShell(connectedIp, 'rm -f /data/local/tmp/ecarxpara.img');

                // 3. Device Tree DTBO
                setProgress(90, 'Menyalin DTBO', 'Membaca partition dtbo_a (8MB)...');
                log('[3/3] Mengambil salinan dtbo_a.img...');
                await runShell(connectedIp, 'dd if=/dev/block/by-name/dtbo_a of=/data/local/tmp/dtbo_a.img bs=4M');
                setProgress(96, 'Memuat Turun DTBO', 'Memuat turun dtbo_a.img...');
                await runAdb(`-s ${connectedIp}:5555 pull /data/local/tmp/dtbo_a.img "${path.join(bDir, 'dtbo_a.img')}"`);
                await runShell(connectedIp, 'rm -f /data/local/tmp/dtbo_a.img');

                setProgress(100, 'Backup Selesai', 'Semua fail disimpan di Desktop/eMAS5_Full_Backup.');
                log(`Backup pantas selesai! Semua fail disimpan di Desktop/eMAS5_Full_Backup.`);
                return sendJson({ success: true, message: `Sandaran Pantas (1.12 GB) Berjaya Disimpan di Folder Desktop/eMAS5_Full_Backup!` });
            }

            if (preset === 'backup_emmc') {
                const bDir = path.join(os.homedir(), 'Desktop', 'eMAS5_Full_eMMC_Dump');
                if (!fs.existsSync(bDir)) fs.mkdirSync(bDir, { recursive: true });
                log(`Memulakan KLON PENUH STORAN eMMC (~45 GB) ke: ${bDir}...`);

                // 1. Super partition (14GB)
                setProgress(5, 'Menyalin Super ROM (~14 GB)', 'Membaca partition super (system, vendor, product)... Sila pijak pedal brek.');
                log('[1/2] Menyalin super.img (~14 GB)...');
                await runShell(connectedIp, 'dd if=/dev/block/by-name/super of=/data/local/tmp/super.img bs=4M');
                setProgress(30, 'Memuat Turun Super ROM', 'Memindahkan super.img ke Laptop via Wi-Fi ADB... Sila pijak pedal brek sekali-sekala.');
                await runAdb(`-s ${connectedIp}:5555 pull /data/local/tmp/super.img "${path.join(bDir, 'super.img')}"`, 1800000); // 30 min timeout
                await runShell(connectedIp, 'rm -f /data/local/tmp/super.img');

                // 2. Userdata partition (31GB)
                setProgress(55, 'Menyalin Userdata (~31 GB)', 'Membaca partition userdata (data pengguna, apps, cache)... Sila pijak pedal brek.');
                log('[2/2] Menyalin userdata.img (~31 GB)...');
                await runShell(connectedIp, 'dd if=/dev/block/by-name/userdata of=/data/local/tmp/userdata.img bs=4M');
                setProgress(80, 'Memuat Turun Userdata', 'Memindahkan userdata.img ke Laptop... Pastikan skrin IHU tidak tidur.');
                await runAdb(`-s ${connectedIp}:5555 pull /data/local/tmp/userdata.img "${path.join(bDir, 'userdata.img')}"`, 3600000); // 60 min timeout
                await runShell(connectedIp, 'rm -f /data/local/tmp/userdata.img');

                setProgress(100, 'Klon Penuh Selesai', 'Seluruh storan kereta (~45 GB) selamat disalin ke Desktop/eMAS5_Full_eMMC_Dump.');
                log(`Klon eMMC (~45 GB) selesai sepenuhnya!`);
                return sendJson({ success: true, message: `Klon Penuh eMMC (~45 GB) Berjaya Disimpan di Folder Desktop/eMAS5_Full_eMMC_Dump!` });
            }

            sendJson({ success: false, message: 'Preset tidak sah.' }, 400);
            } catch (error) {
                log(`Ralat transaksi: ${error.message}`);
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
        log(`e.MAS 5 Desktop Control Center Engine berjalan di: http://127.0.0.1:${PORT}`);
    });
}
module.exports = { server };
