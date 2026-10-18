/**
 * Proton e.MAS 5 — Remote Pilot Core Engine (v2.3)
 * De-slopified Spatial & Antigravity Interactive Controller
 */
(function() {
  'use strict';

  window.logDebug = function(msg) {
    const ts = new Date().toTimeString().split(' ')[0];
    const line = `[${ts}] ${msg}`;
    console.log(line);
    const box = document.getElementById('debugLogBox');
    if (box) {
      box.textContent += line + '\n';
      box.scrollTop = box.scrollHeight;
    }
  };

  const $ = window.$ = id => document.getElementById(id);
  const native = window.AndroidApp || null;
  let apiKey = 'azam7988';
  try {
    if (native && native.apiKey) apiKey = native.apiKey() || apiKey;
    else apiKey = localStorage.getItem('emas_api_key') || apiKey;
  } catch (_) {}

  window.remoteRequestHeaders = () => ({'X-API-Key': apiKey});

  let voiceRecorder = null;
  let currentPreviewAudio = null;
  let activeTab = 'lock';

  let lockSounds = {};
  let activeLockKey = 'mario';
  let lockSyncStatus = 'awaiting_car';
  let unlockSounds = {};
  let activeUnlockKey = 'mario_coin';
  let hornsList = [];
  let presetsList = [];

  let selectedLockFile = null;
  let selectedUnlockFile = null;
  let selectedHornFile = null;
  let megaphoneEnabled = null, presetDirty = false;
  const writesInFlight = new Set();
  function mutationId(){const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);return Array.from(bytes,x=>x.toString(16).padStart(2,'0')).join('');}
  async function requestJson(url, options={}) {
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),20000);
    try {
      const response=await fetch(url,{...options,signal:controller.signal,headers:{'X-API-Key':apiKey,...options.headers}});
      let data;try{data=await response.json();}catch(_){throw Error('Respons server tidak sah. Keputusan belum dapat dipastikan.');}
      if(!response.ok||!data||data.ok!==true)throw Error(data?.error||data?.detail||'Permintaan ditolak oleh server.');
      return data;
    } finally {clearTimeout(timer);}
  }
  function postJson(url,body){return requestJson(url,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':mutationId()},body:JSON.stringify(body)});}
  async function guarded(key,task){
    if(writesInFlight.has(key))return;
    writesInFlight.add(key);
    try{return await task();}finally{writesInFlight.delete(key);}
  }
  function labelButton(className,label,handler,title){const button=document.createElement('button');button.type='button';button.className=className;button.textContent=label;if(title){button.title=title;button.setAttribute('aria-label',title);}button.onclick=handler;return button;}


  // ==================== TOAST & NOTIFICATIONS ====================
  function toast(message, isError = false) {
    const el = $('toast');
    if (!el) return;
    el.textContent = message;
    el.className = 'toast-bubble show' + (isError ? ' error' : '');
    clearTimeout(window.toastTimer);
    window.toastTimer = setTimeout(() => {
      el.className = 'toast-bubble';
    }, 2800);
  }

  function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/[&<>"']/g, m => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[m]));
  }

  // ==================== CONNECTION & STATUS ====================
  async function checkStatus() {
    try {
      const res = await fetch('/api/remote/status', {
        headers: { 'X-API-Key': apiKey },
        cache: 'no-store'
      });
      if (res.ok) {
        const data = await res.json();
        if(data.ok!==true)throw Error('Invalid status');
        const isOnline = data.car && data.car.online;
        $('connDot').className = 'conn-dot ' + (isOnline ? 'online' : 'warn');
        $('connLabel').textContent = isOnline ? 'IHU Kereta Bersambung' : 'VPS Sedia · Kereta Standby';

        if (data.megaphone) {
          const enabled = data.megaphone.enabled === true;
          megaphoneEnabled = enabled;
          $('btnMegaToggle').textContent = enabled ? `Megafon Aktif (${Math.floor(data.megaphone.remaining_sec/60)}m)` : 'Megafon OFF';
          $('btnMegaToggle').style.background = enabled ? 'rgba(16, 185, 129, 0.25)' : 'rgba(16, 185, 129, 0.12)';
        }

        if (window.AndroidApp && window.AndroidApp.updateTelemetry) {
          const t = data.telemetry || {};
          const c = data.car || {};
          window.AndroidApp.updateTelemetry(JSON.stringify({
            soc_pct: t.soc_pct != null ? t.soc_pct : (c.soc != null ? c.soc : (data.soc != null ? data.soc : undefined)),
            range_km: t.range_km != null ? t.range_km : (c.range_km != null ? c.range_km : undefined),
            car_ip: c.ip || t.ip || data.car_ip || '',
            ssid: c.ssid || t.ssid || data.ssid || '',
            locked: typeof c.locked === "boolean" ? c.locked : (typeof t.locked === "boolean" ? t.locked : undefined),
            charging: typeof c.charging === "boolean" ? c.charging : (typeof t.charging === "boolean" ? t.charging : undefined),
            updated_at: t.updated_at || 0
          }));
        }
      } else {
        $('connDot').className = 'conn-dot offline';
        $('connLabel').textContent = 'VPS Tidak Dapat Dicapai';
      }
    } catch (_) {
      $('connDot').className = 'conn-dot offline';
      $('connLabel').textContent = 'Tiada Sambungan Internet';
    }
  }

  async function toggleMegafon(){
    return guarded('switch',async()=>{
      const button=$('btnMegaToggle');button.disabled=true;
      try{if(megaphoneEnabled===null)throw Error('Semak sambungan sebelum menukar suis.');
        const enabled=!megaphoneEnabled;await postJson('/api/megafon/switch',{enabled,duration_min:10});
        megaphoneEnabled=enabled;toast(enabled?'Megafon diaktifkan selama 10 minit.':'Megafon dimatikan.');await checkStatus();
      }catch(error){toast(error.message||'Gagal menukar suis megafon',true);}finally{button.disabled=false;}
    });
  }

  // ==================== HERO PUSH-TO-TALK ====================
  function setupMic() {
    const btn = $('micBtn');
    if (!btn) return;
    voiceRecorder = new window.VoiceRecorder(state => {
      const recording = state === 'recording';
      btn.classList.toggle('recording', recording);
      $('micCancelBar').style.display = recording ? 'block' : 'none';
      $('micStatusText').textContent = ({idle:'Tekan & Tahan', requesting:'Membuka mikrofon…', recording:'Sedang Merakam…', stopping:'Menyiapkan…', uploading:'Menghantar…'})[state];
      $('micSubText').textContent = recording ? 'Lepaskan untuk hantar · maksimum 12 saat' : 'Lepaskan untuk siar ke speaker bumper depan';
      $('micReadyBadge').textContent = recording ? 'Merakam' : state === 'idle' ? 'Sedia' : 'Tunggu';
      $('micReadyBadge').style.background = recording ? 'rgba(239, 68, 68, 0.2)' : 'rgba(16, 185, 129, 0.12)';
      $('micReadyBadge').style.color = recording ? '#9f2f2d' : '#346538';
    }, uploadVoice, err => {
      console.error('Mic error:', err.name, err.message);
      toast('Ralat mikrofon: ' + err.message, true);
      if ((err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') && window.AndroidApp && window.AndroidApp.openAppSettings) {
        setTimeout(() => {
          if (confirm('Kebenaran mikrofon disekat oleh telefon. Buka Tetapan Aplikasi untuk benarkan mikrofon?')) {
            window.AndroidApp.openAppSettings();
          }
        }, 600);
      }
    });
    let activePointer = null;
    btn.addEventListener('pointerdown', e => {
      e.preventDefault();
      logDebug('[PTT] Pointer down. Butang ditekan.');
      if (voiceRecorder.state !== 'idle' || (e.button !== undefined && e.button !== 0)) {
        logDebug('[PTT] Ditolak: state=' + voiceRecorder.state + ', button=' + e.button);
        return;
      }
      activePointer = e.pointerId;
      try { btn.setPointerCapture(e.pointerId); } catch (_) {}
      voiceRecorder.start();
    });
    btn.addEventListener('pointerup', e => {
      logDebug('[PTT] Pointer up. Butang dilepaskan.');
      if (e.pointerId !== activePointer) return;
      activePointer = null;
      voiceRecorder.finish();
    });
    const cancel = () => {
      logDebug('[PTT] Batal / Pointer capture hilang.');
      activePointer = null;
      voiceRecorder.cancel();
    };
    btn.addEventListener('pointercancel', cancel);
    btn.addEventListener('lostpointercapture', () => { if (activePointer !== null) cancel(); });
    $('btnCancelRec').onclick = () => { cancel(); toast('Rakaman dibatalkan'); };
    window.remotePause = cancel;
    document.addEventListener('visibilitychange', () => { if (document.hidden) cancel(); });
    window.addEventListener('pagehide', cancel);
  }

  async function uploadVoice(blob){
    const form=new FormData();form.append('file',blob,blob.type.includes('mp4')?'voice.m4a':'voice.webm');
    try{
      await requestJson('/api/megafon/upload',{method:'POST',headers:{'Idempotency-Key':mutationId()},body:form});
      toast('Rakaman diterima server. Menunggu IHU; bunyi belum disahkan.');
    }catch(error){toast('Rakaman belum disahkan dihantar: '+error.message,true);throw error;}
  }

  // ==================== LOCK SOUND MANAGER ====================
  function updateActiveBadge() {
    const badge = $('activeSoundBadge');
    if (!badge) return;
    const name = (lockSounds[activeLockKey] && lockSounds[activeLockKey].name) || activeLockKey || 'Super Mario Death (2.7s)';
    badge.textContent = 'Pilihan server: ' + name + ' · ' +
      (lockSyncStatus === 'file_synced' ? 'Fail kereta sepadan' : 'Menunggu kereta');
  }

  window.previewAudio = function(url, name) {
    if (currentPreviewAudio) {
      currentPreviewAudio.pause();
      currentPreviewAudio = null;
    }
    toast('Dengar: ' + name);
    currentPreviewAudio = new Audio(url);
    currentPreviewAudio.play().catch(() => toast('Gagal main audio pratonton', true));
  };

  async function loadLockSounds() {
    try {
      const res = await fetch('/api/locksound/list', { headers: { 'X-API-Key': apiKey }, cache: 'no-store' });
      const data = await res.json();
      if (data.ok) {
        lockSounds = data.sounds || {};
        activeLockKey = data.active || 'mario';
        lockSyncStatus = data.sync_status || 'awaiting_car';
        renderLockGrid();
        updateActiveBadge();
      }
    } catch (e) {
      console.error('Failed to load lock sounds', e);
    }
  }

  function renderLockGrid(){
    const grid=$('lockGrid');grid.replaceChildren();
    const presets=[['mario','Super Mario Death (2.7s)','SM'],['guru','Hentikan Guru (6.0s)','HG'],['animewow','Anime "Wow"','AW'],['fah','FAHHHH!','FH'],['quack','Mac Quack','MQ'],['gopgop','Gop Gop Gop','GG'],['tamatlah','Tamatlah Riwayatmu','TR'],['ack','Ack!','AK'],['original','Proton Kilang','EM']].map(([key,name,icon])=>({key,name,icon}));
    for(const key of Object.keys(lockSounds))if(!presets.some(p=>p.key===key))presets.push({key,name:lockSounds[key].name||key,icon:'AU'});
    let hidden=[];try{hidden=JSON.parse(localStorage.getItem('hidden_lock_sounds')||'[]');}catch(_){}
    for(const item of presets.filter(p=>!hidden.includes(p.key))){
      const card=document.createElement('div');card.className='sound-card'+(activeLockKey===item.key?' active':'');
      const meta=document.createElement('div');meta.className='sound-meta';
      const icon=document.createElement('span');icon.className='sound-icon';icon.textContent=item.icon;
      const name=document.createElement('span');name.className='sound-name';name.textContent=item.name;meta.append(icon,name);
      const actions=document.createElement('div');actions.className='sound-actions';
      const url=lockSounds[item.key]?.url||`/static/locksounds/lock_${item.key}.wav`;
      actions.append(labelButton('btn-preview','▶',()=>window.previewAudio(url,item.name),'Dengar '+item.name));
      actions.append(labelButton('btn-apply-sound',activeLockKey===item.key?'Dipilih':'Guna',()=>window.applyLockSound(item.key,item.name)));
      if(item.key!=='original')actions.append(labelButton('btn-delete-sound','×',()=>window.deleteLockSound(item.key,item.name),'Padam '+item.name));
      card.append(meta,actions);grid.append(card);
    }
  }

  window.deleteLockSound=async function(key,name){
    if(!confirm('Padam nada "'+name+'"?'))return;
    return guarded('lock',async()=>{try{await postJson('/api/locksound/delete',{sound:key});
      const hidden=JSON.parse(localStorage.getItem('hidden_lock_sounds')||'[]');if(!hidden.includes(key))hidden.push(key);localStorage.setItem('hidden_lock_sounds',JSON.stringify(hidden));
      await loadLockSounds();toast('Nada dipadam.');}catch(error){toast(error.message,true);}});
  };
  window.applyLockSound=async function(key,name){return guarded('lock',async()=>{try{
    await postJson('/api/locksound/set',{sound:key});activeLockKey=key;lockSyncStatus='awaiting_car';renderLockGrid();updateActiveBadge();toast('Pilihan disimpan di server. Akan diselaraskan apabila kereta tersedia.');
  }catch(error){toast(error.message,true);}});};

  window.onLockFilePick = function(input) {
    selectedLockFile=null;$("lockPickLabel").textContent="Pilih Fail";
    if (input.files && input.files[0]) {
      selectedLockFile = input.files[0];
      $('lockPickLabel').textContent = selectedLockFile.name;
      if (!$('lockNameInput').value.trim()) {
        $('lockNameInput').value = selectedLockFile.name.replace(/\.[^/.]+$/, "");
      }
    }
  };

  window.submitLockUpload=async function(){
    if(!selectedLockFile){toast('Sila pilih fail audio dahulu.',true);return;}
    return guarded('lock-upload',async()=>{const name=$('lockNameInput').value.trim()||'Audio sendiri';const form=new FormData();form.append('file',selectedLockFile);form.append('name',name);
      try{const data=await requestJson('/api/locksound/upload',{method:'POST',headers:{'Idempotency-Key':mutationId()},body:form});
        $('lockFileInput').value='';$('lockNameInput').value='';selectedLockFile=null;$('lockPickLabel').textContent='Pilih Fail';
        await loadLockSounds();toast('Audio disimpan pada server.');if(data.key)await window.applyLockSound(data.key,name);
      }catch(error){toast(error.message,true);}
    });
  };

  // ==================== CUSTOM HORN ====================
  async function loadHorns() {
    try {
      const res = await fetch('/api/megafon/custom/list', { headers: { 'X-API-Key': apiKey } });
      const data = await res.json();
      hornsList = data.horns || [];
      $('hornCountBadge').textContent = hornsList.length + ' Hon';
      renderHorns();
    } catch (_) {}
  }

  function renderHorns(){
    const grid=$('hornGrid');grid.replaceChildren();
    if(!hornsList.length){grid.textContent='Tiada hon tersimpan. Muat naik audio untuk menambah hon.';return;}
    for(const horn of hornsList){const row=document.createElement('div');row.className='horn-card-wrapper';
      row.append(labelButton('btn-blast-horn',horn.name,()=>blastHorn(horn.audio_url,horn.name)));
      row.append(labelButton('btn-delete-sound','×',()=>window.deleteHorn(horn.id,horn.name),'Padam '+horn.name));grid.append(row);}
  }
  window.deleteHorn=async function(id,name){if(!confirm('Padam hon "'+name+'"?'))return;
    return guarded('horn',async()=>{try{await postJson('/api/megafon/custom/delete',{id});await loadHorns();toast('Hon dipadam.');}catch(error){toast(error.message,true);}});
  };
  async function blastHorn(audioUrl,name){return guarded('speech',async()=>{try{
    await postJson('/api/megafon/speak',{audio_url:audioUrl,text:name});toast('Arahan hon diterima server. Bunyi pada IHU belum disahkan.');
  }catch(error){toast(error.message,true);}});}
  window.blastHorn=blastHorn;

  window.onHornFilePick = function(input) {
    selectedHornFile=null;$("hornPickLabel").textContent="Pilih Fail";
    if (input.files && input.files[0]) {
      selectedHornFile = input.files[0];
      $('hornPickLabel').textContent = selectedHornFile.name;
      if (!$('hornNameInput').value.trim()) {
        $('hornNameInput').value = selectedHornFile.name.replace(/\.[^/.]+$/, "");
      }
    }
  };

  window.submitHornUpload=async function(){
    if(!selectedHornFile){toast('Sila pilih fail audio dahulu.',true);return;}
    return guarded('horn-upload',async()=>{const name=$('hornNameInput').value.trim()||'Audio sendiri';const form=new FormData();form.append('file',selectedHornFile);form.append('name',name);
      try{const data=await requestJson('/api/megafon/custom/upload',{method:'POST',headers:{'Idempotency-Key':mutationId()},body:form});
        $('hornFileInput').value='';$('hornNameInput').value='';selectedHornFile=null;$('hornPickLabel').textContent='Pilih Fail';
        await loadHorns();toast('Audio disimpan pada server.');
      }catch(error){toast(error.message,true);}
    });
  };

  // ==================== QUICK PRESETS ====================
  window.openPresetSetup = function() {
    renderPresetSlots();
    $('presetSetupModal').style.display = 'flex';
  };

  window.closePresetSetup = async function() {
    if(presetDirty){
      try{const data=await postJson('/api/megafon/presets/save',{presets:presetsList.map(({id,tag,name})=>({id,tag,name}))});presetsList=data.presets||presetsList;presetDirty=false;renderPresetChips();}
      catch(error){toast('Preset belum disimpan: '+error.message,true);return;}
    }
    $('presetSetupModal').style.display = 'none';
  };

  window.addPresetSlot = function() {
    const newId = 'custom_' + mutationId();
    presetDirty=true;
    presetsList.push({ id: newId, tag: 'Baru', name: 'Suara Baru' });
    renderPresetSlots();
    renderPresetChips();
  };

  function renderPresetSlots() {
    const container = $('presetSlotsContainer');
    if (!container) return;
    container.innerHTML = '';
    presetsList.forEach((p, idx) => {
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;gap:8px;align-items:center;background:rgba(255,255,255,0.03);padding:8px 12px;border-radius:10px;border:1px solid var(--glass-border);';
      row.innerHTML = `
        <input type="text" class="input-text" style="width:70px;padding:6px;" value="${escapeHtml(p.tag)}" placeholder="Tag" onchange="updatePresetTag(${idx}, this.value)">
        <input type="text" class="input-text" style="flex:1;padding:6px;" value="${escapeHtml(p.name)}" placeholder="Ayat Pantas" onchange="updatePresetName(${idx}, this.value)">
        <button type="button" class="btn-preview" style="width:34px;height:34px;" aria-label="Buang preset" onclick="removePresetSlot(${idx})">×</button>
      `;
      container.appendChild(row);
    });
  }

  window.updatePresetTag = function(idx, val) {
    if (presetsList[idx]) {presetsList[idx].tag = val.trim();presetDirty=true;}
    renderPresetChips();
  };

  window.updatePresetName = function(idx, val) {
    if (presetsList[idx]) {presetsList[idx].name = val.trim();presetDirty=true;}
    renderPresetChips();
  };

  window.removePresetSlot = function(idx) {
    presetsList.splice(idx, 1);presetDirty=true;
    renderPresetSlots();
    renderPresetChips();
  };

  async function loadPresets() {
    try {
      const res = await fetch('/api/megafon/presets/list', { headers: { 'X-API-Key': apiKey } });
      const data = await res.json();
      if (data.ok && Array.isArray(data.presets)) {
        presetsList = data.presets;
        renderPresetChips();
      }
    } catch (_) {}
  }

  function renderPresetChips() {
    const grid = $('presetChipsGrid');
    if (!grid) return;
    grid.innerHTML = '';
    presetsList.forEach(p => {
      const chip = document.createElement('button');chip.type='button';
      chip.className = 'preset-chip';
      chip.innerHTML = `
        <span class="preset-chip-tag">${escapeHtml(p.tag)}</span>
        <span class="preset-chip-text">${escapeHtml(p.name)}</span>
      `;
      chip.onclick = () => sendPresetSpeech(p.id, p.name);
      grid.appendChild(chip);
    });
  }

  async function sendPresetSpeech(id,name){return guarded('speech',async()=>{try{
    await postJson('/api/megafon/speak',{preset:id});toast('Arahan suara diterima server. Bunyi pada IHU belum disahkan.');
  }catch(error){toast(error.message,true);}});}


  // ==================== NATIVE BRIDGES & MODALS ====================
  window.openProtonApp = function() {
    if (native && native.openOfficialProtonApp) native.openOfficialProtonApp();
    else window.location.href = 'https://play.google.com/store/apps/details?id=com.malaysia.emas';
  };

  window.pinWidgetToHome = function() {
    if (native && native.pinWidget) {
      native.pinWidget();
      $('settingsModal').style.display = 'none';
    } else {
      toast('Widget hanya tersedia pada aplikasi APK Android');
    }
  };

  window.saveApiKey = function() {
    const key = $('apiKeyInput').value.trim();
    if (!key) {
      toast('Masukkan kunci akses', true);
      return;
    }
    apiKey = key;
    try {
      if (native && native.saveApiKey) native.saveApiKey(key);
      else localStorage.setItem('emas_api_key', key);
    } catch (_) {}
    $('settingsModal').style.display = 'none';
    toast('Kunci akses disimpan!');
    checkStatus();
  };

  $('settingsToggle').onclick = () => {
    $('apiKeyInput').value = apiKey;
    $('settingsModal').style.display = 'flex';
  };

  $('btnMegaToggle').onclick = toggleMegafon;

  // ==================== DEBUG & DIAGNOSTIK ====================
  window.toggleDebugLogs = function() {
    const body = $('debugBody');
    const btn = $('btnToggleDebug');
    if (!body) return;
    const isHidden = body.style.display === 'none';
    body.style.display = isHidden ? 'block' : 'none';
    if (btn) btn.textContent = isHidden ? 'Tutup −' : 'Buka +';
  };

  window.clearDebugLog = function() {
    const box = $('debugLogBox');
    if (box) box.textContent = '';
    logDebug('Log dikosongkan.');
  };

  window.copyDebugLog = function() {
    const box = $('debugLogBox');
    if (!box || !box.textContent.trim()) {
      toast('Tiada log untuk disalin');
      return;
    }
    const text = box.textContent;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => {
        toast('Log berjaya disalin!');
      }).catch(() => fallbackCopy(text));
    } else {
      fallbackCopy(text);
    }
  };

  function fallbackCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      toast('Log berjaya disalin!');
    } catch (_) {
      toast('Sila pilih teks log secara manual.');
    }
    document.body.removeChild(ta);
  }

  window.reRequestMic = function() {
    logDebug('[Manual] Pengguna menekan butang Minta Izin...');
    if (native && native.requestMic) {
      native.requestMic();
    } else if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      navigator.mediaDevices.getUserMedia({ audio: true })
        .then(s => {
          logDebug(' Izin mikrofon browser aktif!');
          s.getTracks().forEach(t => t.stop());
          updateMicBadge();
        })
        .catch(err => {
          logDebug(' Izin browser ditolak: ' + err.name + ' - ' + err.message);
          updateMicBadge();
        });
    }
  };

  window.openPhoneSettings = function() {
    logDebug('[Manual] Membuka tetapan aplikasi telefon...');
    if (native && native.openAppSettings) {
      native.openAppSettings();
    } else {
      toast('Buka Tetapan Telefon > Aplikasi > eMAS Remote');
    }
  };

  window.updateMicBadge = function() {
    const badge = $('micBadgeDebug');
    if (!badge) return;
    if (native && native.hasMicPermission) {
      const ok = native.hasMicPermission();
      badge.textContent = ok ? 'DILEPASKAN (OK)' : 'DISEKAT';
      badge.style.background = ok ? 'rgba(16, 185, 129, 0.25)' : 'rgba(239, 68, 68, 0.25)';
      badge.style.color = ok ? '#346538' : '#9f2f2d';
    } else {
      badge.textContent = 'WEB BROWSER';
      badge.style.background = 'rgba(56, 189, 248, 0.2)';
      badge.style.color = '#1f6c9f';
    }
  };

  window.onMicPermissionUpdated = function(granted) {
    logDebug(`[Callback OS] Status kebenaran mikrofon dikemas kini: granted=${granted}`);
    updateMicBadge();
  };

  window.runDiagnostics=async function(){
    logDebug('Semakan diagnostik sahaja. Tiada rakaman atau upload dibuat.');
    if(native?.checkMicStatus)logDebug('Status Android: '+native.checkMicStatus());
    try{if(navigator.mediaDevices?.enumerateDevices){const devices=await navigator.mediaDevices.enumerateDevices();logDebug('Mikrofon tersedia: '+devices.filter(d=>d.kind==='audioinput').length);}}
    catch(error){logDebug('Status peranti belum tersedia: '+error.name);}
    updateMicBadge();
  };


  window.addEventListener('DOMContentLoaded', () => {
    setupMic();
    updateMicBadge();
    logDebug('eMAS Remote v2.14 dimulakan.');
    if (window.AndroidApp && typeof window.AndroidApp.requestMic === 'function') {
      setTimeout(() => {
        try {
          logDebug('[Init] Meminta kebenaran mic awal...');
          window.AndroidApp.requestMic();
        } catch (_) {}
      }, 350);
    }
    checkStatus();
    loadLockSounds();
    loadHorns();
    loadPresets();
    setInterval(checkStatus, 15000);
    setInterval(() => { if (!document.hidden) loadLockSounds(); }, 5000);
  });

})();
