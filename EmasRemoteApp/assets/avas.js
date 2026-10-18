(function (root) {
  'use strict';
  const MAX_BYTES = 8 * 1024 * 1024;
  function inspectWav(buffer) {
    if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 46 || buffer.byteLength > MAX_BYTES) {
      throw Error('Fail WAV kosong, terlalu kecil atau melebihi 8 MiB.');
    }
    const v = new DataView(buffer);
    const tag = p => String.fromCharCode(v.getUint8(p), v.getUint8(p + 1), v.getUint8(p + 2), v.getUint8(p + 3));
    if (tag(0) !== 'RIFF' || tag(8) !== 'WAVE' || v.getUint32(4, true) + 8 !== buffer.byteLength) {
      throw Error('Fail WAV tidak lengkap atau header RIFF tidak sah.');
    }
    let pos = 12, fmt = false, audio = null;
    while (pos + 8 <= buffer.byteLength) {
      const kind = tag(pos), n = v.getUint32(pos + 4, true), start = pos + 8;
      if (start + n > buffer.byteLength) throw Error('Kandungan WAV terpotong.');
      if (kind === 'fmt ') {
        if (fmt || n < 16 || (n !== 16 && n !== 18)) throw Error('Header format WAV tidak disokong.');
        fmt = true;
        if (v.getUint16(start, true) !== 1 || v.getUint16(start + 2, true) !== 1 ||
            v.getUint32(start + 4, true) !== 48000 || v.getUint32(start + 8, true) !== 96000 ||
            v.getUint16(start + 12, true) !== 2 || v.getUint16(start + 14, true) !== 16 ||
            (n === 18 && v.getUint16(start + 16, true) !== 0)) {
          throw Error('Gunakan WAV PCM 48 kHz, mono, 16-bit.');
        }
      } else if (kind === 'data') {
        if (!fmt || audio !== null || !n || n % 2 || n > 48000 * 2 * 60) {
          throw Error('Data audio tidak sah atau melebihi 60 saat.');
        }
        audio = {start, bytes: n};
      }
      pos = start + n + n % 2;
    }
    if (!fmt || !audio || pos !== buffer.byteLength) throw Error('Struktur WAV tidak lengkap.');
    let audible = false;
    for (let p = audio.start; p < audio.start + audio.bytes; p += 2) {
      if (v.getInt16(p, true) !== 0) { audible = true; break; }
    }
    if (!audible) throw Error('Fail ini mengandungi senyap sahaja.');
    return {duration_seconds: audio.bytes / 96000, bytes: buffer.byteLength};
  }
  const labels = {
    queued: 'Menunggu IHU mengambil arahan.',
    dispatching: 'Arahan telah diambil IHU. Menunggu keputusan.',
    applied: 'IHU mengesahkan perubahan fail dan tetapan. Bunyi fizikal belum disahkan.',
    failed: 'Pertukaran gagal. Semak status sebelum mencuba semula.',
    uncertain: 'Keputusan belum dapat dipastikan. Semak IHU; arahan tidak diulang secara automatik.',
    expired: 'Arahan tamat tempoh sebelum digunakan.'
  };
  root.AvasWav = {inspect: inspectWav, jobLabel: job => labels[job && job.status] || 'Tiada pertukaran baharu.'};
  if (typeof module !== 'undefined') module.exports = root.AvasWav;
  if (!root.document) return;

  const $ = id => document.getElementById(id);
  let upload = null, selected = null, objectUrl = null, busy = false, current = null;
  let timer = null, pickGeneration = 0, pendingRequest = null;
  let processing = false, sourceFile = null, sourceBytes = null, decodedAudio = null, sourceDuration = 0;
  const prefix = '/api/remote/avas';
  const slotName = () => $('avasSlot').selectedOptions[0].textContent;
  function message(text, error) {
    $('avasMessage').textContent = text;
    $('avasMessage').classList.toggle('avas-error', !!error);
  }
  function refreshButtons() {
    const activeJob = current && current.job && ['queued', 'dispatching', 'uncertain'].includes(current.job.status);
    const canChange = !!(current && current.ready && !activeJob && !pendingRequest);
    const locked = busy || processing;
    $('avasUpload').disabled = locked || !selected;
    $('avasApply').disabled = locked || !upload || !canChange;
    $('avasRestore').disabled = locked || !canChange;
    $('avasSlot').disabled = locked;
    $('avasFile').disabled = locked;
    $('avasStart').disabled = locked;
    $('avasDuration').disabled = locked;
    $('avasPrepare').disabled = locked || !sourceFile;
  }
  async function request(path, options) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      const headers = Object.assign({}, root.remoteRequestHeaders ? root.remoteRequestHeaders() : {});
      if (options && options.json) headers['Content-Type'] = 'application/json';
      const response = await fetch(prefix + path, {
        method: (options && options.method) || 'GET', headers, cache: 'no-store', signal: controller.signal,
        body: options && options.json ? JSON.stringify(options.json) : options && options.body
      });
      let data;
      try { data = await response.json(); } catch (_) { throw Error('Respons AVAS pelayan tidak sah.'); }
      if (!response.ok || data.ok !== true) {
        const reason = data.detail || data.error || 'Permintaan AVAS gagal.';
        const error = Error(typeof reason === 'string' ? reason : 'Permintaan AVAS gagal.');
        error.definite = !response.ok;
        throw error;
      }
      return data;
    } finally { clearTimeout(timeout); }
  }
  function requestId() {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, x => x.toString(16).padStart(2, '0')).join('');
  }
  async function readState() {
    if (document.hidden || busy || processing) return;
    try {
      current = await request('/state');
      if (pendingRequest && current.job && current.job.request_id === pendingRequest) {
        pendingRequest = null;
        message(root.AvasWav.jobLabel(current.job), current.job.status === 'uncertain');
      }
      $('avasConnection').textContent = current.ready ? 'IHU AVAS bersambung' :
        current.enabled ? 'IHU AVAS belum sedia' : 'Sambungan AVAS belum disediakan';
      $('avasJob').textContent = root.AvasWav.jobLabel(current.job);
      const config = current.device_config;
      $('avasCurrent').textContent = config && config.active_slot ? 'Slot aktif IHU: ' + config.active_slot : 'Slot aktif IHU belum diterima.';
      if (current.job && current.job.error) $('avasJob').textContent += ' ' + current.job.error;
      refreshButtons();
    } catch (_) {
      current = null;
      $('avasConnection').textContent = 'Sambungan AVAS tidak tersedia';
      refreshButtons();
    }
  }
  function clearPrepared() {
    upload = null; selected = null;
    $('avasPreview').pause();
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = null;
    $('avasPreview').removeAttribute('src');
    $('avasPreview').hidden = true;
  }
  function showPrepared(file, info, start) {
    selected = file;
    objectUrl = URL.createObjectURL(file);
    $('avasPreview').src = objectUrl;
    $('avasPreview').hidden = false;
    message(file.name + ' · 48 kHz · mono · 16-bit · ' + info.duration_seconds.toFixed(2) +
      ' saat · mula ' + Number(start).toFixed(2) + ' saat. Dengar hasil sebelum muat naik.');
  }
  function clipControls() {
    $('avasClip').hidden = false;
    $('avasSourceInfo').textContent = sourceFile.name + ' · panjang asal ' + sourceDuration.toFixed(2) + ' saat';
    $('avasStart').value = '0';
    $('avasStart').max = String(Math.max(0, sourceDuration - 1 / 48000));
    $('avasDuration').value = String(Math.floor(Math.min(15, sourceDuration) * 1000) / 1000);
  }
  async function convertClip(generation) {
    if (!decodedAudio) {
      const decoded = await root.AvasAudio.decode(sourceBytes);
      if (generation !== pickGeneration) return;
      decodedAudio = decoded;
    }
    if (generation !== pickGeneration) return;
    // Let the status paint before encoding up to fifteen seconds of PCM.
    await new Promise(resolve => setTimeout(resolve, 0));
    if (generation !== pickGeneration) return;
    const start = Number($('avasStart').value), duration = Number($('avasDuration').value);
    if ($('avasStart').value.trim() === '' || $('avasDuration').value.trim() === '') throw Error('Isi masa mula dan panjang bahagian lagu.');
    const wav = root.AvasAudio.encode(decodedAudio, start, duration);
    const info = inspectWav(wav);
    const name = (sourceFile.name.replace(/\.[^.]*$/, '').slice(0, 100) || 'avas') + '.wav';
    showPrepared(new File([wav], name, {type: 'audio/wav'}), info, start);
  }
  async function chooseFile() {
    const generation = ++pickGeneration;
    clearPrepared();
    sourceFile = null; sourceBytes = null; decodedAudio = null; sourceDuration = 0;
    $('avasClip').hidden = true;
    const file = $('avasFile').files[0];
    processing = !!file; refreshButtons();
    if (!file) return;
    message('Menyediakan audio pada telefon…');
    try {
      if (!file.size || file.size > root.AvasAudio.maxSourceBytes) throw Error('Pilih fail audio berukuran maksimum 20 MiB.');
      const bytes = await file.arrayBuffer();
      if (generation !== pickGeneration) return;
      let original = null;
      try { original = inspectWav(bytes); } catch (_) { /* Other formats are decoded locally below. */ }
      if (original && original.duration_seconds <= 15) {
        sourceFile = file; sourceBytes = bytes; sourceDuration = original.duration_seconds;
        clipControls(); showPrepared(file, original, 0);
      } else {
        const decoded = await root.AvasAudio.decode(bytes);
        if (generation !== pickGeneration) return;
        sourceFile = file; sourceBytes = bytes; decodedAudio = decoded; sourceDuration = decoded.duration;
        clipControls(); await convertClip(generation);
      }
    } catch (error) {
      if (generation === pickGeneration) message(error.message, true);
    } finally {
      if (generation === pickGeneration) { processing = false; refreshButtons(); }
    }
  }
  function clipChanged() {
    ++pickGeneration;
    clearPrepared();
    message('Bahagian lagu berubah. Tekan Sediakan WAV bahagian ini untuk mendengar dan memuat naik hasil baharu.');
    refreshButtons();
  }
  async function prepareClip() {
    if (busy || processing || !sourceFile) return;
    const generation = ++pickGeneration;
    clearPrepared(); processing = true; refreshButtons();
    message('Menukar bahagian lagu kepada WAV…');
    try { await convertClip(generation); }
    catch (error) { if (generation === pickGeneration) message(error.message, true); }
    finally { if (generation === pickGeneration) { processing = false; refreshButtons(); } }
  }
  async function uploadFile() {
    if (busy || processing || !selected) return;
    busy = true; refreshButtons();
    message('Sedang memuat naik WAV…');
    try {
      const form = new FormData(); form.append('file', selected, selected.name);
      const data = await request('/upload', {method: 'POST', body: form});
      upload = data.upload;
      message('WAV disimpan pada pelayan. Pilih slot dan tekan Gunakan pada IHU.');
    } catch (error) { message(error.name === 'AbortError' ? 'Muat naik tamat masa. Fail belum digunakan pada IHU.' : error.message, true); }
    finally { busy = false; refreshButtons(); }
  }
  async function mutate(action) {
    if (busy || processing || !current || !current.ready || pendingRequest || (action === 'apply' && !upload)) return;
    const slot = Number($('avasSlot').value);
    if (!root.confirm(action === 'restore' ? 'Pulihkan bunyi asal untuk ' + slotName() + '?' :
      'Gunakan ' + upload.name + ' pada ' + slotName() + ' dan aktifkan slot ini?')) return;
    busy = true; refreshButtons();
    const id = requestId();
    const body = {slot, request_id: id};
    if (action === 'apply') body.upload_id = upload.upload_id;
    pendingRequest = id;
    try {
      const data = await request('/' + action, {method: 'POST', json: body});
      pendingRequest = null;
      current.job = data.job;
      $('avasJob').textContent = root.AvasWav.jobLabel(data.job);
      message('Arahan direkodkan. Menunggu pengesahan daripada IHU.');
    } catch (error) {
      if (error.definite) pendingRequest = null;
      message(error.definite ? error.message : 'Respons terputus; keputusan belum pasti. Semak sambungan untuk menyemak status. Arahan tidak dihantar semula.', true);
    } finally { busy = false; refreshButtons(); await readState(); }
  }
  function startPolling() {
    clearInterval(timer); timer = null;
    if (!document.hidden) { readState(); timer = setInterval(readState, 10000); }
  }
  document.addEventListener('DOMContentLoaded', () => {
    if (!$('avasFile')) return;
    $('avasFile').addEventListener('change', chooseFile);
    $('avasStart').addEventListener('input', clipChanged);
    $('avasDuration').addEventListener('input', clipChanged);
    $('avasPrepare').addEventListener('click', prepareClip);
    $('avasUpload').addEventListener('click', uploadFile);
    $('avasApply').addEventListener('click', () => mutate('apply'));
    $('avasRestore').addEventListener('click', () => mutate('restore'));
    $('avasRefresh').addEventListener('click', readState);
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) $('avasPreview').pause();
      startPolling();
    });
    root.addEventListener('pagehide', () => { clearInterval(timer); $('avasPreview').pause(); });
    root.addEventListener('pageshow', startPolling);
    refreshButtons(); startPolling();
  });
})(typeof window !== 'undefined' ? window : globalThis);
