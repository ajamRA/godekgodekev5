// EmasTool Desktop Frontend Client
let currentSlots = [];
let selectedSlot = null;

// Task navigation keeps forms and running operations intact when switching pages.
const pageIds = ['mula', 'aplikasi', 'audio', 'servis'];
function showPage() {
    const requested = window.location.hash.slice(1);
    const activePage = pageIds.includes(requested) ? requested : 'mula';
    document.querySelectorAll('.page').forEach(page => {
        page.hidden = page.id !== activePage;
    });
    document.querySelectorAll('.nav-link').forEach(link => {
        const active = link.hash === `#${activePage}`;
        link.classList.toggle('active', active);
        if (active) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    });
}
window.addEventListener('hashchange', () => {
    showPage();
    window.scrollTo(0, 0);
    const heading = document.querySelector('.page:not([hidden]) h1');
    heading.setAttribute('tabindex', '-1');
    heading.focus({ preventScroll: true });
});
showPage();

const statusBulb = document.getElementById('statusBulb');
const connTitle = document.getElementById('connTitle');
const connSub = document.getElementById('connSub');
const inputCarIp = document.getElementById('inputCarIp');
const btnAutoScan = document.getElementById('btnAutoScan');
const btnManualConnect = document.getElementById('btnManualConnect');
const btnRefreshSlots = document.getElementById('btnRefreshSlots');
const slotsList = document.getElementById('slotsList');
const selectTargetSlot = document.getElementById('selectTargetSlot');
const inputCustomAppName = document.getElementById('inputCustomAppName');
const btnInstallCustom = document.getElementById('btnInstallCustom');
const dropzone = document.getElementById('dropzone');
const dropText = document.getElementById('dropText');
const fileApkInput = document.getElementById('fileApkInput');
const consoleLogs = document.getElementById('consoleLogs');

const selectLockSound = document.getElementById('selectLockSound');
const btnApplyLockSound = document.getElementById('btnApplyLockSound');
const fileUploadLock = document.getElementById('fileUploadLock');
const btnBrowseLock = document.getElementById('btnBrowseLock');
const txtLockFile = document.getElementById('txtLockFile');

const selectAvasSlot = document.getElementById('selectAvasSlot');
const selectAvasSound = document.getElementById('selectAvasSound');
const btnApplyAvasSound = document.getElementById('btnApplyAvasSound');
const fileUploadAvas = document.getElementById('fileUploadAvas');
const btnBrowseAvas = document.getElementById('btnBrowseAvas');
const txtAvasFile = document.getElementById('txtAvasFile');

const btnBackupIdentity = document.getElementById('btnBackupIdentity');
const btnBackupAll = document.getElementById('btnBackupAll');
const btnBackupEmmc = document.getElementById('btnBackupEmmc');

// Live Progress Bar Elements
const taskProgressSection = document.getElementById('taskProgressSection');
const taskLabel = document.getElementById('taskLabel');
const taskPct = document.getElementById('taskPct');
const taskFill = document.getElementById('taskFill');
const taskDetail = document.getElementById('taskDetail');

function logToUi(msg) {
    const time = new Date().toLocaleTimeString();
    const div = document.createElement('div');
    div.textContent = `[${time}] ${msg}`;
    consoleLogs.appendChild(div);
    document.getElementById('latestLog').textContent = msg;
    consoleLogs.scrollTop = consoleLogs.scrollHeight;
}

function setConnected(ip, model = 'IHU801P') {
    statusBulb.classList.add('connected');
    connTitle.textContent = `Bersambung ke e.MAS 5 · ${ip}:5555`;
    connTitle.style.color = 'var(--status-green)';
    connSub.textContent = `Model: ${model} · Port ADB 5555 Aktif · Akses Root Disahkan`;
    inputCarIp.value = ip;
    document.getElementById('connectionChip').textContent = 'Kereta bersambung';
}

function setDisconnected() {
    statusBulb.classList.remove('connected');
    connTitle.textContent = 'Kereta belum dijumpai';
    connTitle.style.color = 'var(--ink)';
    connSub.textContent = 'Semak laptop dan kereta menggunakan Wi-Fi yang sama, kemudian cuba cari semula atau masukkan IP secara manual.';
    document.getElementById('connectionChip').textContent = 'Belum bersambung';
}

// Auto-Scan Wi-Fi
async function autoScan() {
    logToUi('🔍 Memulakan imbasan automatik Wi-Fi untuk mencari kereta...');
    btnAutoScan.disabled = true;
    btnAutoScan.textContent = 'Sedang mencari kereta…';

    try {
        const res = await fetch('/api/scan');
        const data = await res.json();
        if (data.success) {
            logToUi(`[+] Kereta Proton e.MAS 5 dikesan di alamat IP: ${data.ip}!`);
            setConnected(data.ip, data.model);
            await refreshSlots();
        } else {
            logToUi(data.message || 'Tiada kereta dikesan.');
            setDisconnected();
        }
    } catch (e) {
        logToUi(`Ralat imbasan: ${e.message}`);
    } finally {
        btnAutoScan.disabled = false;
        btnAutoScan.textContent = 'Cari kereta di Wi-Fi';
    }
}

// Manual Connect
async function manualConnect() {
    const ip = inputCarIp.value.trim();
    if (!ip) return alert('Sila masukkan alamat IP kereta.');

    logToUi(`Menyambung manual ke ${ip}:5555...`);
    btnManualConnect.disabled = true;

    try {
        const res = await fetch('/api/connect', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ip })
        });
        const data = await res.json();
        if (data.success) {
            logToUi(`Berjaya disambungkan ke ${ip}!`);
            setConnected(data.ip, data.model);
            await refreshSlots();
        } else {
            alert(`Gagal menyambung: ${data.message}`);
            logToUi(`Gagal menyambung: ${data.message}`);
        }
    } catch (e) {
        logToUi(`Ralat sambungan: ${e.message}`);
    } finally {
        btnManualConnect.disabled = false;
    }
}

// Refresh Scanned Slots
async function refreshSlots() {
    btnRefreshSlots.disabled = true;
    logToUi('Mengimbas senarai aplikasi terpasang pada kereta (A-Store & Sistem)...');

    try {
        const res = await fetch('/api/slots');
        const data = await res.json();
        if (data.success) {
            currentSlots = data.slots;
            renderSlots(currentSlots);
            logToUi(`Imbasan selesai: ${currentSlots.length} slot aplikasi ditemui.`);
        } else {
            logToUi(`Ralat slot: ${data.message}`);
        }
    } catch (e) {
        logToUi(`Ralat mengimbas slot: ${e.message}`);
    } finally {
        btnRefreshSlots.disabled = false;
    }
}

function renderSlots(slots) {
    slotsList.innerHTML = '';
    selectedSlot = null;
    selectTargetSlot.innerHTML = '<option value="">-- Pilih slot sasaran dari senarai --</option>';

    if (!slots || slots.length === 0) {
        slotsList.innerHTML = '<div class="slot-empty-notice">Tiada slot aplikasi ditemui.</div>';
        return;
    }

    slots.forEach((slot, index) => {
        const statusText = slot.isOverwritten ? `Diganti: ${slot.overwrittenBy}` : 'Aplikasi asal';
        const isOccupied = slot.isOverwritten;

        // Create Slot Item in ListBox
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'slot-item';
        item.innerHTML = `
            <div class="slot-meta">
                <div class="slot-name-row">
                    <span class="slot-title">${slot.name}</span>
                    <span class="badge-type">${slot.type}</span>
                </div>
                <span class="slot-pkg">${slot.packageName}</span>
            </div>
            <div>
                <span class="slot-badge-status ${isOccupied ? '' : 'empty'}">
                    ${isOccupied ? statusText : 'Aplikasi asal'}
                </span>
            </div>
        `;

        item.addEventListener('click', () => {
            document.querySelectorAll('.slot-item').forEach(i => i.classList.remove('selected'));
            item.classList.add('selected');
            selectedSlot = slot;
            selectTargetSlot.value = index;
            inputCustomAppName.value = slot.name + ' Mod';
            logToUi(`Slot dipilih: ${slot.name} (${slot.packageName}) · Status: ${statusText}`);
        });

        slotsList.appendChild(item);

        // Add to Dropdown with clear visual status
        const opt = document.createElement('option');
        opt.value = index;
        const allowed = !slot.policy || slot.policy.allowed;
        const iconPrefix = !allowed ? '[L]' : (isOccupied ? '[!]' : '[+]');
        const polTag = !allowed ? ' — DILINDUNGI' : '';
        opt.textContent = `${iconPrefix} [${slot.type}] ${slot.name} — ${statusText}${polTag}`;
        if (!allowed) opt.style.color = '#c9a227';
        selectTargetSlot.appendChild(opt);
    });
}

selectTargetSlot.addEventListener('change', () => {
    const idx = selectTargetSlot.value;
    if (idx !== '') {
        selectedSlot = currentSlots[idx];
        const allItems = document.querySelectorAll('.slot-item');
        allItems.forEach(i => i.classList.remove('selected'));
        if (allItems[idx]) allItems[idx].classList.add('selected');
    }
});

// Drag & Drop File
dropzone.addEventListener('click', () => fileApkInput.click());

// P2 FIX: dulu UI hanya menyimpan NAMA fail, kemudian prompt() minta user taip
// laluan penuh di laptop (dan menghantar laluan itu ke server). Sekarang fail
// APK benar-benar dimuat naik ke server, jadi drag & drop betul-betul berfungsi.
let chosenApkFile = null;
let uploadedApk = { name: null, path: null, size: 0 };

async function uploadApkToServer(file) {
    logToUi(`Memuat naik ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB) ke enjin tempatan...`);
    const res = await fetch('/api/upload-apk', {
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream', 'X-File-Name': file.name },
        body: file
    });
    const data = await res.json();
    if (!data.success) throw new Error(data.message || 'Muat naik gagal.');
    uploadedApk = { name: file.name, path: data.path, size: file.size };
    logToUi(`Muat naik selesai: ${file.name}`);
    return data;
}

async function handleApkPicked(file) {
    if (!file) return;
    const okApk = /\.apk$/i.test(file.name) || file.name.endsWith('.apks') || file.name.endsWith('.xapk');
    if (!okApk) {
        alert('Sila pilih fail .apk sahaja.');
        return;
    }
    chosenApkFile = file;
    dropText.textContent = `Fail dipilih: ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB) — memuat naik...`;
    try {
        await uploadApkToServer(file);
        dropText.textContent = `Fail sedia: ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB)`;
        const baseName = file.name.replace(/\.(apk|apks|xapk)$/i, '');
        inputCustomAppName.value = baseName;
    } catch (e) {
        dropText.textContent = `Gagal memuat naik: ${e.message}`;
        logToUi(`Ralat muat naik APK: ${e.message}`);
        chosenApkFile = null;
        uploadedApk = { name: null, path: null, size: 0 };
    }
}

fileApkInput.addEventListener('change', (e) => {
    if (e.target.files.length > 0) handleApkPicked(e.target.files[0]);
});

dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.style.borderColor = 'var(--accent)';
});

dropzone.addEventListener('dragleave', () => {
    dropzone.style.borderColor = 'var(--border)';
});

dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.style.borderColor = 'var(--border)';
    if (e.dataTransfer.files.length > 0) handleApkPicked(e.dataTransfer.files[0]);
});

// Install Custom APK to Slot
btnInstallCustom.addEventListener('click', async () => {
    if (!selectedSlot) {
        return alert('Sila pilih satu slot dari senarai di atas terlebih dahulu!');
    }
    if (!uploadedApk.path) {
        return alert('Sila pilih/tarik fail .apk terlebih dahulu (tunggu sehingga "Fail sedia").');
    }

    const customName = inputCustomAppName.value.trim() || 'CustomApp';
    logToUi(`Memulakan pemasangan ${customName} ke slot ${selectedSlot.name}...`);
    btnInstallCustom.disabled = true;
    btnInstallCustom.textContent = '⏳ SEDANG MEMASANG...';

    try {
        const res = await fetch('/api/install-slot', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                targetSlot: selectedSlot,
                localApkPath: uploadedApk.path,
                customAppName: customName
            })
        });
        const data = await res.json();
        if (data.success) {
            alert(data.message);
            logToUi(data.message);
            await refreshSlots();
        } else {
            alert(`Ralat: ${data.message}`);
            logToUi(`Ralat: ${data.message}`);
        }
    } catch (e) {
        logToUi(`Ralat pemasangan: ${e.message}`);
    } finally {
        btnInstallCustom.disabled = false;
        btnInstallCustom.textContent = 'Gantikan slot & pasang APK';
    }
});

// 1-Click Presets
async function triggerPreset(preset, label, extra = {}) {
    if (!confirm(`Adakah anda ingin menjalankan: ${label}?`)) return;

    logToUi(`Menjalankan: ${label}...`);
    try {
        const res = await fetch('/api/install-preset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ preset, ...extra })
        });
        const data = await res.json();
        alert(data.message);
        logToUi(data.message);
        await refreshSlots();
    } catch (e) {
        logToUi(`Ralat: ${e.message}`);
    }
}

document.getElementById('btnSmartTube').addEventListener('click', () => triggerPreset('smarttube', 'Pasang SmartTube atas slot TikTok'));
document.getElementById('btnNetflix').addEventListener('click', () => triggerPreset('netflix', 'Pasang Netflix atas slot JOOX Music'));
document.getElementById('btnSteering').addEventListener('click', () => triggerPreset('steering', 'Pasang Mod Butang Stereng (Hold 5s)'));
document.getElementById('btnStealth').addEventListener('click', () => triggerPreset('restore_stock', 'Aktifkan Stealth Mode'));
document.getElementById('btnStealthOff').addEventListener('click', () => triggerPreset('stealth_off', 'Matikan Stealth — Pulih Mod Normal'));
document.getElementById('btnStealthStatus').addEventListener('click', () => triggerPreset('stealth_status', 'Semak Status Sistem'));
document.getElementById('btnDriveInstall').addEventListener('click', () => triggerPreset('drivemode_install', 'Mulakan Kawalan Stereng'));
document.getElementById('btnDriveRemove').addEventListener('click', () => triggerPreset('drivemode_remove', 'Hentikan Kawalan Stereng'));
document.getElementById('btnReboot').addEventListener('click', () => triggerPreset('reboot', 'Reboot Skrin IHU'));
document.getElementById('btnScreenshot').addEventListener('click', () => triggerPreset('screenshot', 'Ambil Screenshot IHU'));

// Audio & Backup Controls
btnApplyLockSound.addEventListener('click', () => {
    const choice = selectLockSound.value;
    const label = choice === 'original' ? 'Standard Kilang (Factory)' : choice.toUpperCase();
    triggerPreset('locksound', `Tukar Nada Kunci kepada ${label}`, { soundChoice: choice });
});

btnApplyAvasSound.addEventListener('click', () => {
    const choice = selectAvasSound.value;
    const slot = selectAvasSlot.value;
    const label = choice === 'factory' ? 'Standard Kilang' : choice.toUpperCase();
    triggerPreset('avas', `Tukar Bunyi AVAS (Slot ${slot}) kepada ${label}`, { soundChoice: choice, slotNum: parseInt(slot, 10) });
});

// Custom Audio Upload Handlers
btnBrowseLock.addEventListener('click', () => fileUploadLock.click());
fileUploadLock.addEventListener('change', async (e) => {
    if (e.target.files.length > 0) {
        const file = e.target.files[0];
        txtLockFile.textContent = `${file.name} (${(file.size / 1024).toFixed(1)} KB)`;
        
        if (!confirm(`Tukar Nada Kunci kereta menggunakan fail audio: ${file.name}?\nSistem akan auto-convert fail ini ke WAV 48kHz Stereo 16-bit PCM.`)) {
            return;
        }

        logToUi(`Memuat naik dan menukar audio Nada Kunci: ${file.name}...`);
        btnBrowseLock.disabled = true;
        btnBrowseLock.textContent = '⏳ SEDANG CONVERT & PASANG...';

        try {
            const res = await fetch('/api/upload-convert-audio', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/octet-stream',
                    'X-Audio-Type': 'lock',
                    'X-File-Name': file.name
                },
                body: file
            });
            const data = await res.json();
            alert(data.message);
            logToUi(data.message);
        } catch (err) {
            alert(`Ralat: ${err.message}`);
            logToUi(`Ralat: ${err.message}`);
        } finally {
            btnBrowseLock.disabled = false;
            btnBrowseLock.textContent = 'Pilih & pasang audio sendiri';
            fileUploadLock.value = '';
        }
    }
});

btnBrowseAvas.addEventListener('click', () => fileUploadAvas.click());
fileUploadAvas.addEventListener('change', async (e) => {
    if (e.target.files.length > 0) {
        const file = e.target.files[0];
        const slot = selectAvasSlot.value;
        txtAvasFile.textContent = `[Slot ${slot}] ${file.name} (${(file.size / 1024).toFixed(1)} KB)`;
        
        if (!confirm(`Tukar Bunyi AVAS Pejalan Kaki (Slot ${slot}) menggunakan: ${file.name}?\nSistem akan auto-convert fail ini ke WAV 48kHz Mono 16-bit PCM.`)) {
            return;
        }

        logToUi(`Memuat naik dan menukar audio AVAS Slot ${slot}: ${file.name}...`);
        btnBrowseAvas.disabled = true;
        btnBrowseAvas.textContent = '⏳ SEDANG CONVERT & PASANG...';

        try {
            const res = await fetch('/api/upload-convert-audio', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/octet-stream',
                    'X-Audio-Type': 'avas',
                    'X-Slot-Num': slot,
                    'X-File-Name': file.name
                },
                body: file
            });
            const data = await res.json();
            alert(data.message);
            logToUi(data.message);
        } catch (err) {
            alert(`Ralat: ${err.message}`);
            logToUi(`Ralat: ${err.message}`);
        } finally {
            btnBrowseAvas.disabled = false;
            btnBrowseAvas.textContent = 'Pilih & pasang audio sendiri';
            fileUploadAvas.value = '';
        }
    }
});

if (btnBackupIdentity) {
    btnBackupIdentity.addEventListener('click', () => {
        triggerPreset('backup_identity', 'Sandaran Identiti & Kalibrasi Unik (~60 MB: proinfo, nvcfg, nvram, pki)');
    });
}

btnBackupAll.addEventListener('click', () => {
    triggerPreset('backup_all', 'Sandaran Pantas Sistem Kenderaan (~1.12 GB: boot_a, ecarxpara, dtbo)');
});

btnBackupEmmc.addEventListener('click', () => {
    const confirmed = confirm(
        "⚠️ AMARAN KLON PENUH eMMC (~45 GB):\n\n" +
        "1. Proses ini akan menyalin super.img (14 GB) dan userdata.img (31 GB).\n" +
        "2. Mengambil masa lebih kurang 30 hingga 60 minit melalui Wi-Fi.\n" +
        "3. Anda WAJIB duduk di kerusi pemandu & PIJAK PEDAL BREK sekali-sekala agar IHU tidak 'sleep'!\n\n" +
        "Adakah anda bersedia dan ingin memulakan sekarang?"
    );
    if (confirmed) {
        triggerPreset('backup_emmc', 'Klon Penuh Storan eMMC (~45 GB: super & userdata)');
    }
});

// Event listeners
btnAutoScan.addEventListener('click', autoScan);
btnManualConnect.addEventListener('click', manualConnect);
btnRefreshSlots.addEventListener('click', refreshSlots);

// === LIVE TASK PROGRESS POLLING (Real-time %) ===
let progressPollInterval = null;

function updateProgressUi(task) {
    if (!task) return;
    if (task.active || (task.progress > 0 && task.progress < 100)) {
        taskProgressSection.style.display = 'block';
        taskLabel.textContent = (task.label || 'SEDANG MEMPROSES...').toUpperCase();
        taskPct.textContent = `${task.progress}%`;
        taskFill.style.width = `${task.progress}%`;
        taskDetail.textContent = task.detail || '';
    } else if (task.progress >= 100) {
        taskPct.textContent = '100%';
        taskFill.style.width = '100%';
        taskDetail.textContent = task.detail || 'Selesai!';
        setTimeout(() => {
            taskProgressSection.style.display = 'none';
        }, 3000);
    } else {
        taskProgressSection.style.display = 'none';
    }
}

async function checkTaskStatus() {
    try {
        const res = await fetch('/api/status');
        if (res.ok) {
            const data = await res.json();
            if (data.task) {
                updateProgressUi(data.task);
            }
        }
    } catch (e) {
        // ignore poll errors
    }
}

// Start polling task progress every 500ms
setInterval(checkTaskStatus, 500);

// === AUTOMATIC SELF-SCAN PADA WAKTU BUKA APP ===
async function startup() {
    logToUi('Memulakan e.MAS 5 Desktop Control Center...');
    
    // Tunggu server backend sedia (retry jika baru bermula)
    for (let i = 0; i < 15; i++) {
        try {
            const res = await fetch('/api/status');
            if (res.ok) {
                const data = await res.json();
                if (data.connected) {
                    setConnected(data.ip, data.model);
                    await refreshSlots();
                    return;
                }
                break;
            }
        } catch (e) {
            await new Promise(r => setTimeout(r, 300));
        }
    }

    // Automatik jalankan self-scan terus!
    await autoScan();
}

// Window control handlers (Minimize & Close)
document.getElementById('winBtnClose')?.addEventListener('click', async () => {
    try {
        await fetch('/api/window/close', { method: 'POST' });
    } catch (e) {}
    window.close();
});

document.getElementById('winBtnMin')?.addEventListener('click', async () => {
    try {
        await fetch('/api/window/minimize', { method: 'POST' });
    } catch (e) {}
});

window.addEventListener('DOMContentLoaded', startup);
