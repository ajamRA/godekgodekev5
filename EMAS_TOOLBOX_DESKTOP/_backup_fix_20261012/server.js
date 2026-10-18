const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { exec } = require('child_process');
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

function setProgress(progress, label = null, detail = null) {
    currentTask.active = progress >= 0 && progress < 100;
    currentTask.progress = Math.min(100, Math.max(0, Math.round(progress)));
    if (label !== null) currentTask.label = label;
    if (detail !== null) currentTask.detail = detail;
    if (progress >= 100) {
        currentTask.active = false;
    }
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

    // Check mount list
    const mountRes = await runShell(ip, 'mount | grep -iE "custom_apps|SmartTube|Netflix|EmasEnergy"');
    const mounts = mountRes.stdout || '';

    const slots = [];
    const seenNames = new Set();

    // 1. Guaranteed System Slots on e.MAS 5 / Geely EX2
    const knownSystemDirs = [
        {
            name: 'TikTok',
            paths: ['/system/app/ttincar_tiktok_ACO', '/system/app/TikTok'],
            defaultPkg: 'com.zhiliaoapp.musically',
            checkMod: (m) => m.includes('SmartTube') ? 'SmartTube' : null
        },
        {
            name: 'JOOX Music',
            paths: ['/system/app/JOOXMusic_ACO', '/system/app/JOOX'],
            defaultPkg: 'com.tencent.ibg.joox',
            checkMod: (m) => m.includes('Netflix') ? 'Netflix' : null
        },
        {
            name: 'TheNoor',
            paths: ['/system/app/Noor/Noor.apk', '/system/app/Noor', '/system/app/TheNoor_ACO', '/system/app/Noor_ACO'],
            defaultPkg: 'com.noor.thenoorcar',
            checkMod: (m) => m.includes('EmasEnergy') || m.includes('Noor_ACO') ? 'EmasEnergy' : null
        },
        {
            name: 'Audio+',
            paths: ['/system/app/AudioPlus_ACO', '/product/app/AudioPlus_ACO', '/system/app/AudioPlus'],
            defaultPkg: 'com.malaysia.media',
            checkMod: (m) => m.includes('SurCam') ? 'SurCam' : (m.includes('TDashCam') ? 'TDashCam' : null)
        }
    ];

    for (const sys of knownSystemDirs) {
        let existingPath = sys.paths[0];
        // verify which path exists on the car
        for (const p of sys.paths) {
            const checkExists = await runShell(ip, `[ -e "${p}" ] && echo "EXISTS"`);
            if (checkExists.stdout.includes('EXISTS')) {
                existingPath = p;
                break;
            }
        }

        let isOverwritten = false;
        let overwrittenBy = 'Tiada';

        const lsRes = await runShell(ip, `ls "${existingPath}"/*.apk 2>/dev/null || ls "${existingPath}" 2>/dev/null`);
        const filesInside = lsRes.stdout || '';

        if (filesInside.includes('SmartTube')) {
            isOverwritten = true;
            overwrittenBy = 'SmartTube';
        } else if (filesInside.includes('netflix') || filesInside.includes('Netflix')) {
            isOverwritten = true;
            overwrittenBy = 'Netflix';
        } else if (filesInside.includes('EmasEnergy') || filesInside.includes('Noor_ACO')) {
            isOverwritten = true;
            overwrittenBy = 'EmasEnergy';
        } else if (sys.name === 'TheNoor') {
            // Check if Noor.apk is actually EmasEnergy (by size > 4MB or containing index.html / Cockpit EV)
            const noorCheck = await runShell(ip, 'stat -c %s /system/app/Noor/Noor.apk 2>/dev/null || echo 0');
            const noorSize = parseInt(noorCheck.stdout.trim(), 10) || 0;
            if (noorSize > 3500000) { // OEM Noor is ~1.5MB; EmasEnergy is 4.76MB
                isOverwritten = true;
                overwrittenBy = 'EmasEnergy';
            }
        } else if (filesInside.includes('SurCam') || filesInside.includes('TDashCam')) {
            isOverwritten = true;
            overwrittenBy = 'SurCam';
        }

        seenNames.add(sys.name);
        slots.push({
            name: sys.name,
            packageName: sys.defaultPkg,
            targetPath: existingPath,
            type: 'SYSTEM',
            isOverwritten,
            overwrittenBy
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

                const isOverwritten = mounts.includes(apkPath) || mounts.includes(pkg);
                let overwrittenBy = 'Tiada';
                if (isOverwritten) {
                    const matchOver = mounts.match(new RegExp(`/custom_apps/([^/\\s]+)`));
                    if (matchOver) overwrittenBy = matchOver[1];
                }

                let friendlyName = friendlyNames[pkg] || pkg.split('.').pop();
                friendlyName = friendlyName.charAt(0).toUpperCase() + friendlyName.slice(1);

                slots.push({
                    name: friendlyName,
                    packageName: pkg,
                    targetPath: apkPath,
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

// HTTP Server
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const pathname = url.pathname;

    const sendJson = (data, code = 200) => {
        res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
        res.end(JSON.stringify(data));
    };

    if (req.method === 'OPTIONS') {
        res.writeHead(200, {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type'
        });
        res.end();
        return;
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
                await runAdb(`-s ${connectedIp}:5555 push "${localApkPath}" "${remoteApk}"`);

                setProgress(60, `Mengkonfigurasi ${customAppName}`, 'Menetapkan kebenaran & SELinux context...');
                log('[3/6] Mengkonfigurasi kebenaran fail & konteks SELinux...');
                const selinuxContext = targetSlot.type === 'SYSTEM' ? 'u:object_r:system_file:s0' : 'u:object_r:apk_data_file:s0';
                await runShell(connectedIp, `chmod 755 ${remoteDir} && chmod 644 ${remoteApk}`);
                await runShell(connectedIp, `chcon -R ${selinuxContext} ${remoteDir}`);

                setProgress(75, `Mengikat Slot ${targetSlot.name}`, 'Melaksanakan Virtual Bind-Mount...');
                log('[4/6] Melaksanakan Virtual Bind-Mount...');
                await runShell(connectedIp, `umount -l "${targetSlot.targetPath}" 2>/dev/null; umount -l $(dirname "${targetSlot.targetPath}") 2>/dev/null || true`);

                const targetDir = path.dirname(targetSlot.targetPath).replace(/\\/g, '/');
                await runShell(connectedIp, `mount -o bind "${remoteApk}" "${targetSlot.targetPath}" 2>/dev/null || mount -o bind "${remoteDir}" "${targetDir}"`);

                setProgress(88, `Mendaftarkan ${customAppName}`, 'Mendaftar dengan Package Manager...');
                log('[5/6] Mendaftarkan dengan Package Manager...');
                await runShell(connectedIp, `pm install -r -d -g "${remoteApk}" 2>/dev/null || true`);

                setProgress(95, 'Kemas kini Ketahanan Boot', 'Mengemas kini run_binds.sh & reload launcher...');
                log('[6/6] Mengemaskini sistem ketahanan reboot (run_binds.sh)...');
                const scriptPath = '/data/local/custom_apps/run_binds.sh';
                await runShell(connectedIp, `touch ${scriptPath} && chmod 755 ${scriptPath}`);
                await runShell(connectedIp, `sed -i '/${safeName}/d' ${scriptPath} 2>/dev/null || true`);
                await runShell(connectedIp, `echo "mount none ${remoteDir} ${targetDir} bind rec # ${safeName}" >> ${scriptPath}`);

                // Refresh launcher
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');

                setProgress(100, 'Selesai', `Berjaya memasang ${customAppName}!`);
                log(`Pemasangan selesai! ${customAppName} kini aktif di atas slot ${targetSlot.name}.`);
                sendJson({ success: true, message: `Berjaya memasang ${customAppName} di atas slot ${targetSlot.name}!` });
            } catch (e) {
                setProgress(100, 'Ralat', e.message);
                sendJson({ success: false, message: e.message }, 500);
            }
        });
        return;
    }

    if (pathname === '/api/upload-convert-audio' && req.method === 'POST') {
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', async () => {
            try {
                const totalBuf = Buffer.concat(chunks);
                const targetType = req.headers['x-audio-type'] || 'lock'; // 'lock' (48k stereo) or 'avas' (48k mono)
                const originalName = req.headers['x-file-name'] || 'uploaded_sound.mp3';
                const slotNum = req.headers['x-slot-num'] || '1';

                log(`Menerima fail audio: ${originalName} (${(totalBuf.length / 1024).toFixed(1)} KB) untuk mod: ${targetType.toUpperCase()}...`);

                const tempDir = path.join(__dirname, 'temp_audio');
                if (!fs.existsSync(tempDir)) fs.mkdirSync(tempDir, { recursive: true });

                const inputExt = path.extname(originalName) || '.mp3';
                const inputTemp = path.join(tempDir, `input_${Date.now()}${inputExt}`);
                const outputWav = path.join(tempDir, `output_${targetType}_${Date.now()}.wav`);

                fs.writeFileSync(inputTemp, totalBuf);

                // Convert with FFmpeg
                // Lock sound: 48000Hz, stereo (2 channels), 16-bit PCM
                // AVAS sound: 48000Hz, mono (1 channel), 16-bit PCM
                const channels = targetType === 'avas' ? 1 : 2;
                log(`Menukar format audio kepada WAV 48,000Hz 16-bit PCM (${channels === 1 ? 'MONO' : 'STEREO'})...`);

                await new Promise((resolve, reject) => {
                    const cmd = `"${FFMPEG}" -i "${inputTemp}" -y -ar 48000 -ac ${channels} -c:a pcm_s16le "${outputWav}"`;
                    exec(cmd, (err, stdout, stderr) => {
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
                        const sNum = parseInt(slotNum, 10) || 1;
                        log(`Memindahkan audio baru ke AVAS Pejalan Kaki (Slot ${sNum})...`);
                        const slotFolders = {
                            1: { dir: '/vendor/etc/avas/sound_type_1', file: 'yinheyinfu.wav' },
                            2: { dir: '/vendor/etc/avas/sound_type_2', file: 'space.wav' },
                            3: { dir: '/vendor/etc/avas/sound_type_3', file: 'pixel.wav' }
                        };
                        const targetSlotConfig = slotFolders[sNum] || slotFolders[1];

                        await runShell(connectedIp, 'mkdir -p /data/local/avas');
                        const remoteWav = `/data/local/avas/custom_slot_${sNum}.wav`;
                        await runAdb(`-s ${connectedIp}:5555 push "${outputWav}" "${remoteWav}"`);
                        await runShell(connectedIp, `chmod 644 "${remoteWav}"`);
                        await runShell(connectedIp, `umount -l ${targetSlotConfig.dir}/${targetSlotConfig.file} 2>/dev/null || true`);
                        await runShell(connectedIp, `mount -o bind "${remoteWav}" "${targetSlotConfig.dir}/${targetSlotConfig.file}"`);
                        await runShell(connectedIp, `restorecon -F ${targetSlotConfig.dir}/${targetSlotConfig.file} 2>/dev/null || true`);
                        await runShell(connectedIp, 'pkill -f "vendor.molead.hardware.avas" 2>/dev/null || true');
                        log(`Bunyi AVAS Slot ${sNum} tersuai berjaya dipasang dan aktif di kereta!`);
                        return sendJson({ success: true, message: `Bunyi AVAS Slot ${sNum} tersuai berjaya dipasang & diaktifkan (${originalName})!` });
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
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            const extra = JSON.parse(body);
            const { preset, soundChoice } = extra;
            if (!connectedIp) return sendJson({ success: false, message: 'Kereta belum bersambung.' });

            if (preset === 'smarttube') {
                log('Memasang SmartTube (slot TikTok)...');
                const apk = getPresetPath('apps/SmartTube.apk');
                if (!apk) return sendJson({ success: false, message: 'SmartTube.apk tidak dijumpai.' });

                const remoteDir = '/data/local/custom_apps/SmartTube';
                await runShell(connectedIp, `mkdir -p ${remoteDir}`);
                await runAdb(`-s ${connectedIp}:5555 push "${apk}" "${remoteDir}/SmartTube.apk"`);
                await runShell(connectedIp, `chmod 755 ${remoteDir} && chmod 644 ${remoteDir}/*.apk`);
                await runShell(connectedIp, `chcon -R u:object_r:system_file:s0 ${remoteDir}`);

                // Detect tiktok dir (ttincar_tiktok_ACO or TikTok)
                const tiktokDirRes = await runShell(connectedIp, '[ -d /system/app/ttincar_tiktok_ACO ] && echo "/system/app/ttincar_tiktok_ACO" || echo "/system/app/TikTok"');
                const tiktokDir = tiktokDirRes.stdout.trim() || '/system/app/ttincar_tiktok_ACO';

                await runShell(connectedIp, `mount -o bind "${remoteDir}/SmartTube.apk" "${tiktokDir}/${path.basename(tiktokDir)}.apk" 2>/dev/null || mount -o bind "${remoteDir}" "${tiktokDir}"`);
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log('SmartTube berjaya dipasang!');
                return sendJson({ success: true, message: 'SmartTube berjaya dipasang di atas slot TikTok!' });
            }

            if (preset === 'netflix') {
                log('Memasang Netflix (slot JOOX)...');
                const apk = getPresetPath('apps/Netflix.apk');
                if (!apk) return sendJson({ success: false, message: 'Netflix.apk tidak dijumpai.' });

                const remoteDir = '/data/local/custom_apps/Netflix';
                await runShell(connectedIp, `mkdir -p ${remoteDir}`);
                await runAdb(`-s ${connectedIp}:5555 push "${apk}" "${remoteDir}/Netflix.apk"`);
                await runShell(connectedIp, `chmod 755 ${remoteDir} && chmod 644 ${remoteDir}/*.apk`);
                await runShell(connectedIp, `chcon -R u:object_r:system_file:s0 ${remoteDir}`);
                await runShell(connectedIp, `mount -o bind "${remoteDir}/Netflix.apk" /system/app/JOOXMusic_ACO/JOOXMusic_ACO.apk 2>/dev/null || mount -o bind "${remoteDir}" /system/app/JOOXMusic_ACO`);
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log('Netflix berjaya dipasang!');
                return sendJson({ success: true, message: 'Netflix berjaya dipasang di atas slot JOOX Music!' });
            }

            if (preset === 'energy_app') {
                log('Memasang EmasEnergy App Bateri (slot TheNoor)...');
                await runShell(connectedIp, 'sh /data/local/custom_apps/deploy_energy_app.sh 2>/dev/null || true');
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log('EmasEnergy App siap dipasang!');
                return sendJson({ success: true, message: 'EmasEnergy Battery Monitor berjaya dipasang pada slot TheNoor!' });
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
                    // Restore original factory lock sound
                    await runShell(connectedIp, 'chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'cp /data/local/window/locksounds/lock_original.wav /sdcard/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'cp /data/local/window/locksounds/lock_original.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'chmod 444 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'killall -HUP ecarx_reverse_radar 2>/dev/null || pkill -f ecarx_reverse_radar 2>/dev/null || true');
                    log('Nada kunci asal kilang (Original Factory) telah dipulihkan!');
                    return sendJson({ success: true, message: 'Nada kunci kenderaan berjaya dipulihkan kepada Original Factory!' });
                }

                if (choice === 'custom' && req.customAudioPath) {
                    localWav = req.customAudioPath;
                } else {
                    const soundFile = choice === 'mario' ? 'lock_mario.wav' : (choice === 'animewow' ? 'lock_animewow.wav' : 'lock_original.wav');
                    localWav = getPresetPath(`locksound/${soundFile}`);
                }

                if (localWav && fs.existsSync(localWav)) {
                    await runShell(connectedIp, 'mkdir -p /data/local/window/locksounds /data/local/tmp');
                    await runAdb(`-s ${connectedIp}:5555 push "${localWav}" /data/local/tmp/new_lock.wav`);
                    await runShell(connectedIp, 'chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'cp /data/local/tmp/new_lock.wav /sdcard/V3_Vehicle_Locked_Single.wav && cp /data/local/tmp/new_lock.wav /data/media/0/V3_Vehicle_Locked_Single.wav');
                    await runShell(connectedIp, 'cp /data/local/tmp/new_lock.wav /data/local/window/locksounds/lock_active.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'chmod 444 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null || true');
                    await runShell(connectedIp, 'rm -f /data/local/tmp/new_lock.wav');
                    await runShell(connectedIp, 'killall -HUP ecarx_reverse_radar 2>/dev/null || pkill -f ecarx_reverse_radar 2>/dev/null || true');
                    log(`Nada kunci (${choice}) berjaya diaktifkan!`);
                    return sendJson({ success: true, message: `Nada kunci kenderaan berjaya ditukar kepada mod: ${choice.toUpperCase()}!` });
                } else {
                    return sendJson({ success: false, message: 'Fail nada kunci tidak ditemui.' }, 404);
                }
            }

            if (preset === 'avas') {
                const choice = soundChoice || 'v8';
                const slotNum = extra.slotNum || 1; // Slot 1, 2, or 3
                log(`Menukar Bunyi Pejalan Kaki AVAS (Slot ${slotNum} -> ${choice})...`);

                const slotFolders = {
                    1: { dir: '/vendor/etc/avas/sound_type_1', file: 'yinheyinfu.wav', factory: '/data/local/avas/factory_backup/yinheyinfu.wav' },
                    2: { dir: '/vendor/etc/avas/sound_type_2', file: 'space.wav', factory: '/data/local/avas/factory_backup/space.wav' },
                    3: { dir: '/vendor/etc/avas/sound_type_3', file: 'pixel.wav', factory: '/data/local/avas/factory_backup/pixel.wav' }
                };
                const targetSlotConfig = slotFolders[slotNum] || slotFolders[1];

                if (choice === 'factory' || choice === 'original') {
                    // Restore factory sound on this slot
                    await runShell(connectedIp, `umount -l ${targetSlotConfig.dir}/${targetSlotConfig.file} 2>/dev/null || true`);
                    if (targetSlotConfig.factory) {
                        await runShell(connectedIp, `[ -f ${targetSlotConfig.factory} ] && mount -o bind ${targetSlotConfig.factory} ${targetSlotConfig.dir}/${targetSlotConfig.file}`);
                        await runShell(connectedIp, `restorecon -F ${targetSlotConfig.dir}/${targetSlotConfig.file} 2>/dev/null || true`);
                    }
                    await runShell(connectedIp, 'pkill -f "vendor.molead.hardware.avas" 2>/dev/null || true');
                    log(`AVAS Slot ${slotNum} berjaya dipulihkan ke standard kilang!`);
                    return sendJson({ success: true, message: `AVAS Slot ${slotNum} berjaya dipulihkan ke Standard Kilang!` });
                }

                let localAvas = null;
                if (choice === 'custom' && req.customAudioPath) {
                    localAvas = req.customAudioPath;
                } else {
                    const avasFile = choice === 'v8' ? 'clean_dodge_v8.wav' : (choice === 'mario' ? 'clean_slot3_mario.wav' : 'clean_paddlepop.wav');
                    localAvas = getPresetPath(`avas/presets/${avasFile}`);
                }

                if (localAvas && fs.existsSync(localAvas)) {
                    await runShell(connectedIp, 'mkdir -p /data/local/avas');
                    const remoteWav = `/data/local/avas/custom_slot_${slotNum}.wav`;
                    await runAdb(`-s ${connectedIp}:5555 push "${localAvas}" "${remoteWav}"`);
                    await runShell(connectedIp, `chmod 644 "${remoteWav}"`);
                    await runShell(connectedIp, `umount -l ${targetSlotConfig.dir}/${targetSlotConfig.file} 2>/dev/null || true`);
                    await runShell(connectedIp, `mount -o bind "${remoteWav}" "${targetSlotConfig.dir}/${targetSlotConfig.file}"`);
                    await runShell(connectedIp, `restorecon -F ${targetSlotConfig.dir}/${targetSlotConfig.file} 2>/dev/null || true`);
                    await runShell(connectedIp, 'pkill -f "vendor.molead.hardware.avas" 2>/dev/null || true');
                    log(`Bunyi AVAS Slot ${slotNum} (${choice}) berjaya ditukar!`);
                    return sendJson({ success: true, message: `Bunyi amaran pejalan kaki AVAS (Slot ${slotNum}) berjaya ditukar kepada: ${choice.toUpperCase()}!` });
                } else {
                    return sendJson({ success: false, message: 'Fail preset AVAS tidak ditemui.' }, 404);
                }
            }

            if (preset === 'restore_stock') {
                log('Mengaktifkan Stealth Mode: Menyah-pasang semua mount custom apps...');
                await runShell(connectedIp, 'umount -l /system/app/ttincar_tiktok_ACO 2>/dev/null || true');
                await runShell(connectedIp, 'umount -l /system/app/TikTok 2>/dev/null || true');
                await runShell(connectedIp, 'umount -l /system/app/JOOXMusic_ACO 2>/dev/null || true');
                await runShell(connectedIp, 'umount -l /system/app/AudioPlus_ACO 2>/dev/null || true');
                await runShell(connectedIp, 'umount -l /product/app/AudioPlus_ACO 2>/dev/null || true');
                await runShell(connectedIp, 'umount -l /system/app/Noor 2>/dev/null || true');
                await runShell(connectedIp, 'umount -l /system/app/TheNoor_ACO 2>/dev/null || true');
                await runShell(connectedIp, 'for m in $(grep custom_apps /proc/mounts | awk \'{print $2}\'); do umount -l $m; done 2>/dev/null || true');
                await runShell(connectedIp, 'pkill -f ecarx.launcher || am force-stop ecarx.launcher 2>/dev/null || true');
                log('Kereta kini kembali ke standard kilang (Showroom Condition)!');
                return sendJson({ success: true, message: 'Semua modifikasi telah disembunyikan. Kereta kini 100% standard kilang!' });
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

server.listen(PORT, '127.0.0.1', () => {
    log(`e.MAS 5 Desktop Control Center Engine berjalan di: http://127.0.0.1:${PORT}`);
});
