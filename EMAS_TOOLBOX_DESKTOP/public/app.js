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
let transferRunning = false;
let backupRequestPending = false;
let closingConfirmed = false;

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
    connTitle.textContent = `Connected to e.MAS 5 · ${ip}:5555`;
    connTitle.style.color = 'var(--status-green)';
    connSub.textContent = `Model: ${model} · Wi-Fi ADB port 5555 · Root access verified`;
    inputCarIp.value = ip;
    document.getElementById('connectionChip').textContent = 'Connected';
}

function setDisconnected() {
    statusBulb.classList.remove('connected');
    connTitle.textContent = 'Car not found';
    connTitle.style.color = 'var(--ink)';
    connSub.textContent = 'Check that your PC and IHU share the same Wi-Fi network. Scan again or connect using the IHU IP address.';
    document.getElementById('connectionChip').textContent = 'Disconnected';
}

// Auto-Scan Wi-Fi
async function autoScan() {
    logToUi('Scanning the local Wi-Fi network for your IHU…');
    btnAutoScan.disabled = true;
    btnAutoScan.textContent = 'Scanning for car…';

    try {
        const res = await fetch('/api/scan');
        const data = await res.json();
        if (data.success) {
            logToUi(`Proton e.MAS 5 found at ${data.ip}.`);
            setConnected(data.ip, data.model);
            await refreshSlots();
        } else {
            logToUi(data.message || 'No car found.');
            setDisconnected();
        }
    } catch (e) {
        logToUi(`Scan failed: ${e.message}`);
    } finally {
        btnAutoScan.disabled = false;
        btnAutoScan.textContent = 'Scan for car';
    }
}

// Manual Connect
async function manualConnect() {
    const ip = inputCarIp.value.trim();
    if (!ip) return alert('Enter the IHU IP address first.');

    logToUi(`Connecting to ${ip}:5555 over Wi-Fi ADB…`);
    btnManualConnect.disabled = true;

    try {
        const res = await fetch('/api/connect', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ip })
        });
        const data = await res.json();
        if (data.success) {
            logToUi(`Connected to ${ip}.`);
            setConnected(data.ip, data.model);
            await refreshSlots();
        } else {
            alert(`Connection failed: ${data.message}`);
            logToUi(`Connection failed: ${data.message}`);
        }
    } catch (e) {
        logToUi(`ADB connection error: ${e.message}`);
    } finally {
        btnManualConnect.disabled = false;
    }
}

// Refresh Scanned Slots
async function refreshSlots() {
    btnRefreshSlots.disabled = true;
    logToUi('Scanning installed app slots on the IHU (A-Store & system)…');

    try {
        const res = await fetch('/api/slots');
        const data = await res.json();
        if (data.success) {
            currentSlots = data.slots;
            renderSlots(currentSlots);
            logToUi(`Scan complete: ${currentSlots.length} app slots found.`);
        } else {
            logToUi(`Slot scan failed: ${data.message}`);
        }
    } catch (e) {
        logToUi(`Slot scan error: ${e.message}`);
    } finally {
        btnRefreshSlots.disabled = false;
    }
}

function renderSlots(slots) {
    slotsList.innerHTML = '';
    selectedSlot = null;
    selectTargetSlot.innerHTML = '<option value="">Select a target slot from the list</option>';

    if (!slots || slots.length === 0) {
        slotsList.innerHTML = '<div class="slot-empty-notice">No app slots found. Check the ADB connection and refresh slots.</div>';
        return;
    }

    slots.forEach((slot, index) => {
        const statusText = slot.isOverwritten ? `Overlaid: ${slot.overwrittenBy}` : 'Original app';
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
                    ${isOccupied ? statusText : 'Original app'}
                </span>
            </div>
        `;

        item.addEventListener('click', () => {
            document.querySelectorAll('.slot-item').forEach(i => i.classList.remove('selected'));
            item.classList.add('selected');
            selectedSlot = slot;
            selectTargetSlot.value = index;
            inputCustomAppName.value = slot.name + ' Mod';
            logToUi(`Selected slot: ${slot.name} (${slot.packageName}) · ${statusText}`);
        });

        slotsList.appendChild(item);

        // Add to Dropdown with clear visual status
        const opt = document.createElement('option');
        opt.value = index;
        const allowed = !slot.policy || slot.policy.allowed;
        const iconPrefix = !allowed ? '[L]' : (isOccupied ? '[!]' : '[+]');
        const polTag = !allowed ? ' — PROTECTED' : '';
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
    logToUi(`Uploading ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB) to the local toolbox server…`);
    const res = await fetch('/api/upload-apk', {
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream', 'X-File-Name': file.name },
        body: file
    });
    const data = await res.json();
    if (!data.success) throw new Error(data.message || 'APK upload failed.');
    uploadedApk = { name: file.name, path: data.path, size: file.size };
    logToUi(`APK upload complete: ${file.name}`);
    return data;
}

async function handleApkPicked(file) {
    if (!file) return;
    const okApk = /\.apk$/i.test(file.name) || file.name.endsWith('.apks') || file.name.endsWith('.xapk');
    if (!okApk) {
        alert('Choose an APK file.');
        return;
    }
    chosenApkFile = file;
    dropText.textContent = `Selected: ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB) — uploading…`;
    try {
        await uploadApkToServer(file);
        dropText.textContent = `Ready: ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB)`;
        const baseName = file.name.replace(/\.(apk|apks|xapk)$/i, '');
        inputCustomAppName.value = baseName;
    } catch (e) {
        dropText.textContent = `Upload failed: ${e.message}`;
        logToUi(`APK upload error: ${e.message}`);
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
        return alert('Select the app slot you want to replace first.');
    }
    if (!uploadedApk.path) {
        return alert('Choose an APK and wait until the file shows “Ready”.');
    }

    const customName = inputCustomAppName.value.trim() || 'CustomApp';
    logToUi(`Installing ${customName} over the ${selectedSlot.name} slot…`);
    btnInstallCustom.disabled = true;
    btnInstallCustom.textContent = 'Installing slot overlay…';

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
            alert(`Install failed: ${data.message}`);
            logToUi(`Install failed: ${data.message}`);
        }
    } catch (e) {
        logToUi(`APK installation error: ${e.message}`);
    } finally {
        btnInstallCustom.disabled = false;
        btnInstallCustom.textContent = 'Replace slot & install APK';
    }
});

// 1-Click Presets
async function triggerPreset(preset, label, extra = {}) {
    if (!confirm(`Run on the connected IHU: ${label}?`)) return;
    const backup = preset.startsWith('backup_');
    if (backup) {
        backupRequestPending = true;
        updateProgressUi({ active: true, status: 'running', progress: null, label: 'Preparing backup', detail: label, transfer: { bytesTransferred: 0, totalBytes: null, bytesPerSecond: null, etaSeconds: null } });
        taskProgressSection.scrollIntoView({ behavior: 'instant', block: 'start' });
    }

    logToUi(`Running: ${label}…`);
    try {
        const res = await fetch('/api/install-preset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ preset, ...extra })
        });
        const data = await res.json();
        alert(data.message);
        logToUi(data.message);
        if (backup) await checkTaskStatus();
        await refreshSlots();
    } catch (e) {
        logToUi(`Operation failed: ${e.message}`);
        if (backup) taskDetail.textContent = 'The backup request could not be confirmed. Keep the IHU awake while Toolbox checks its status.';
    } finally {
        if (backup) backupRequestPending = false;
    }
}

document.getElementById('btnSmartTube').addEventListener('click', () => triggerPreset('smarttube', 'Install SmartTube over the TikTok slot'));
document.getElementById('btnNetflix').addEventListener('click', () => triggerPreset('netflix', 'Install Netflix over the JOOX Music slot'));
document.getElementById('btnSteering').addEventListener('click', () => triggerPreset('steering', 'Install and start the steering controller on the IHU (Drive Mode, regen & Stealth shortcut)'));
document.getElementById('btnStealth').addEventListener('click', () => triggerPreset('restore_stock', 'Enable Stealth using the service-mode script already installed on the IHU'));
document.getElementById('btnStealthOff').addEventListener('click', () => triggerPreset('stealth_off', 'Disable Stealth and restore normal mod operation on the IHU'));
document.getElementById('btnStealthStatus').addEventListener('click', () => triggerPreset('stealth_status', 'Check installed mod status on the IHU'));
document.getElementById('btnDriveInstall').addEventListener('click', () => triggerPreset('drivemode_install', 'Start the steering controller already installed on the IHU'));
document.getElementById('btnDriveRemove').addEventListener('click', () => triggerPreset('drivemode_remove', 'Stop the running steering controller (files and auto-start remain installed)'));
document.getElementById('btnReboot').addEventListener('click', () => triggerPreset('reboot', 'Reboot the IHU'));
document.getElementById('btnScreenshot').addEventListener('click', () => triggerPreset('screenshot', 'Save an IHU screenshot to your PC'));

// Audio & Backup Controls
btnApplyLockSound.addEventListener('click', () => {
    const choice = selectLockSound.value;
    const label = choice === 'original' ? 'Factory original' : choice.toUpperCase();
    triggerPreset('locksound', `Apply lock chime: ${label}`, { soundChoice: choice });
});

btnApplyAvasSound.addEventListener('click', () => {
    const choice = selectAvasSound.value;
    const slot = selectAvasSlot.value;
    const label = choice === 'factory' ? 'Factory original' : choice.toUpperCase();
    triggerPreset('avas', `Apply AVAS sound to slot ${slot}: ${label}`, { soundChoice: choice, slotNum: parseInt(slot, 10) });
});

// Custom Audio Upload Handlers
btnBrowseLock.addEventListener('click', () => fileUploadLock.click());
fileUploadLock.addEventListener('change', async (e) => {
    if (e.target.files.length > 0) {
        const file = e.target.files[0];
        txtLockFile.textContent = `${file.name} (${(file.size / 1024).toFixed(1)} KB)`;
        
        if (!confirm(`Apply ${file.name} as the IHU lock chime?\nThe file will be converted to WAV 48 kHz stereo 16-bit PCM and installed on the car.`)) {
            return;
        }

        logToUi(`Uploading and converting lock chime: ${file.name}…`);
        btnBrowseLock.disabled = true;
        btnBrowseLock.textContent = 'Converting & applying…';

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
            alert(`Audio operation failed: ${err.message}`);
            logToUi(`Audio operation failed: ${err.message}`);
        } finally {
            btnBrowseLock.disabled = false;
            btnBrowseLock.textContent = 'Upload & apply custom audio';
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
        
        if (!confirm(`Apply ${file.name} to AVAS slot ${slot} on the IHU?\nThe file will be converted to WAV 48 kHz mono 16-bit PCM and installed on the car.`)) {
            return;
        }

        logToUi(`Uploading and converting AVAS audio for slot ${slot}: ${file.name}…`);
        btnBrowseAvas.disabled = true;
        btnBrowseAvas.textContent = 'Converting & applying…';

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
            alert(`AVAS operation failed: ${err.message}`);
            logToUi(`AVAS operation failed: ${err.message}`);
        } finally {
            btnBrowseAvas.disabled = false;
            btnBrowseAvas.textContent = 'Upload & apply custom audio';
            fileUploadAvas.value = '';
        }
    }
});

if (btnBackupIdentity) {
    btnBackupIdentity.addEventListener('click', () => {
        triggerPreset('backup_identity', 'Create identity & calibration backup (~60 MB) on your PC');
    });
}

btnBackupAll.addEventListener('click', () => {
    triggerPreset('backup_all', 'Create recovery backup (~1.12 GB: boot_a, ecarxpara, dtbo_a) on your PC');
});

btnBackupEmmc.addEventListener('click', async () => {
    if (!confirm(
        'LARGE PARTITION DUMP — ABOUT 45 GB\n\n' +
        'This reads super and userdata, not the entire raw eMMC. Actual sizes depend on the IHU.\n' +
        'Keep enough free disk space on your PC. Stay in the driver’s seat while safely parked and press the brake periodically to prevent IHU sleep.\n' +
        'Do not close this app, let the PC sleep, disconnect Wi-Fi ADB or reboot the IHU until the dump finishes.\n' +
        'Live userdata is not an offline snapshot. An interrupted transfer leaves a .partial image; there is no automatic resume.\n\n' +
        'Start the partition dump now?'
    )) return;
    backupRequestPending = true;
    updateProgressUi({ active: true, status: 'running', progress: null, label: 'Preparing partition dump', detail: 'Reading partition sizes from the IHU. Keep ADB connected.', transfer: { bytesTransferred: 0, totalBytes: null, bytesPerSecond: null, etaSeconds: null } });
    taskProgressSection.scrollIntoView({ behavior: 'instant', block: 'start' });
    try {
        const res = await fetch('/api/install-preset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ preset: 'backup_emmc' })
        });
        const data = await res.json();
        logToUi(data.message);
        if (!res.ok || !data.success) {
            updateProgressUi({ active: false, status: 'failed', progress: null, label: 'Partition dump failed', detail: data.message });
        } else {
            await checkTaskStatus();
        }
    } catch (e) {
        taskLabel.textContent = 'Backup status unknown';
        taskDetail.textContent = `Could not confirm the dump result: ${e.message}. Keep the IHU awake while Toolbox checks its status.`;
        logToUi(`Partition dump request failed: ${e.message}`);
    } finally {
        backupRequestPending = false;
    }
});

// Event listeners
btnAutoScan.addEventListener('click', autoScan);
btnManualConnect.addEventListener('click', manualConnect);
btnRefreshSlots.addEventListener('click', refreshSlots);

// Progress is measured only where the backend supplies byte counters.
function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes < 0) return '—';
    if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(2)} GB`;
    if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(1)} MB`;
    if (bytes >= 1e3) return `${(bytes / 1e3).toFixed(1)} KB`;
    return `${Math.round(bytes)} B`;
}

function formatEta(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return 'Calculating…';
    if (seconds === 0) return 'Finishing…';
    const minutes = Math.ceil(seconds / 60);
    return minutes >= 60 ? `~${Math.floor(minutes / 60)}h ${minutes % 60}m` : `~${minutes} min`;
}

function updateProgressUi(task) {
    if (!task) return;
    if (backupRequestPending && !task.active) return;
    const failed = task.status === 'failed';
    const completed = task.status === 'completed';
    const visible = task.active || failed || completed || (Number.isFinite(task.progress) && task.progress > 0);
    taskProgressSection.style.display = visible ? 'block' : 'none';
    taskProgressSection.classList.toggle('task-failed', failed);
    taskProgressSection.classList.toggle('task-completed', completed);
    taskProgressSection.setAttribute('aria-busy', String(!!task.active));
    transferRunning = !!task.active && !!task.transfer;
    btnBackupEmmc.disabled = !!task.active;
    if (!visible) return;
    const determinate = Number.isFinite(task.progress);
    const pct = determinate ? Math.min(100, Math.max(0, task.progress)) : null;
    const bar = document.getElementById('taskProgressBar');
    taskLabel.textContent = task.label || 'Processing…';
    taskDetail.textContent = task.detail || '';
    const verifying = task.transfer?.totalBytes > 0 && task.transfer.bytesTransferred >= task.transfer.totalBytes;
    taskPct.textContent = failed ? 'Failed' : completed ? 'Complete' : determinate ? `${Math.floor(pct * 10) / 10}%` : verifying ? 'Verifying…' : 'Preparing…';
    bar.classList.toggle('indeterminate', !determinate && !!task.active);
    if (determinate) bar.setAttribute('aria-valuenow', String(pct));
    else bar.removeAttribute('aria-valuenow');
    taskFill.style.width = pct === null ? '0%' : `${pct}%`;
    const transfer = task.transfer;
    document.getElementById('transferStats').hidden = !transfer;
    document.getElementById('transferWarning').hidden = !transfer || !task.active;
    if (transfer) {
        document.getElementById('transferBytes').textContent = `${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.totalBytes)}`;
        document.getElementById('transferSpeed').textContent = failed || completed ? '—' : Number.isFinite(transfer.bytesPerSecond) ? `${(transfer.bytesPerSecond / 1e6).toFixed(2)} MB/s` : 'Measuring…';
        document.getElementById('transferEta').textContent = failed ? 'Interrupted' : completed ? 'Finished' : formatEta(transfer.etaSeconds);
    }
}

async function checkTaskStatus() {
    try {
        const res = await fetch('/api/status');
        if (!res.ok) throw new Error(`Status request returned HTTP ${res.status}`);
        const data = await res.json();
        if (data.task) updateProgressUi(data.task);
    } catch (e) {
        if (transferRunning) {
            document.getElementById('transferSpeed').textContent = 'Unavailable';
            document.getElementById('transferEta').textContent = 'Unavailable';
            taskDetail.textContent = 'Cannot reach the toolbox server. Transfer status is unknown; check the connection and backup files before retrying.';
        }
    }
}

// Start polling task progress every 500ms
setInterval(checkTaskStatus, 500);

// === AUTOMATIC SELF-SCAN PADA WAKTU BUKA APP ===
async function startup() {
    logToUi('Starting e.MAS 5 Toolbox…');
    
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

// Native windows have custom controls; regular browsers use their own caption controls.
const nativeWindow = window.chrome?.webview;
document.querySelector('.window-controls').hidden = !nativeWindow;
document.getElementById('winBtnMax').hidden = !nativeWindow;

window.requestDesktopClose = () => {
    if ((transferRunning || backupRequestPending) && !confirm('A backup is running. Closing Toolbox stops its server and can interrupt the transfer, leaving a partial image. Close anyway?')) return false;
    closingConfirmed = true;
    return true;
};

document.getElementById('winBtnClose').addEventListener('click', () => {
    if (nativeWindow) nativeWindow.postMessage('close');
});

window.addEventListener('beforeunload', (event) => {
    if ((!transferRunning && !backupRequestPending) || closingConfirmed) return;
    event.preventDefault();
    event.returnValue = '';
});

document.getElementById('winBtnMin').addEventListener('click', () => {
    if (nativeWindow) nativeWindow.postMessage('minimize');
});

document.getElementById('winBtnMax').addEventListener('click', () => {
    if (nativeWindow) nativeWindow.postMessage('maximize');
});

window.addEventListener('DOMContentLoaded', startup);
