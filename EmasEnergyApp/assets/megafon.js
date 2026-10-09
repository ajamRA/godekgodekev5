// EmasEnergy Megafon & Exterior Speaker Client (0ms Local Latency)
// Direct Hardware Control for ALSA pcmC0D29p via MegaphoneDaemon (Port 8890)

window.MegafonUI = (function() {
  const MEGAFON_HOST = 'http://127.0.0.1:8890';
  let isDaemonOnline = false;
  let isPlaying = false;
  let isRecording = false;
  let mediaRecorder = null;
  let audioChunks = [];
  let pollTimer = null;
  let recordingStartTime = 0;
  let recTimer = null;

  const PRESETS = [
    { id: 'alihkan', name: 'Alihkan Kereta', desc: 'Tolong alihkan kenderaan anda', icon: '📢', file: 'alihkan.wav' },
    { id: 'salam', name: 'Beri Salam', desc: 'Assalamualaikum', icon: '🕌', file: 'salam.wav' },
    { id: 'tepi', name: 'Minta Laluan', desc: 'Minta laluan ke tepi', icon: '🚨', file: 'tepi.wav' },
    { id: 'datang', name: 'Saya Datang', desc: 'Saya datang sekejap', icon: '⏳', file: 'datang.wav' }
  ];

  async function checkStatus() {
    try {
      const res = await fetch(MEGAFON_HOST + '/api/status', { method: 'GET', signal: AbortSignal.timeout(2000) });
      if (res.ok) {
        const data = await res.json();
        isDaemonOnline = true;
        isPlaying = !!data.playing;
        updateUIStatus();
        return;
      }
    } catch (e) {
      // Fallback: Check if native AndroidEnergy bridge exists
      if (window.AndroidEnergy && typeof window.AndroidEnergy.playMegafonPreset === 'function') {
        isDaemonOnline = true;
      } else {
        isDaemonOnline = false;
      }
    }
    updateUIStatus();
  }

  function updateUIStatus() {
    const statusBadge = document.getElementById('megafon-status-badge');
    if (statusBadge) {
      if (isPlaying) {
        statusBadge.innerHTML = '<span class="status-dot live-pulse"></span> SEDANG BERCAKAP DI SPEAKER';
        statusBadge.style.color = 'var(--accent, #79dfc2)';
      } else if (isDaemonOnline) {
        statusBadge.innerHTML = '<span class="status-dot online"></span> AKTIF (0ms Bumper Hardware)';
        statusBadge.style.color = 'var(--accent, #79dfc2)';
      } else {
        statusBadge.innerHTML = '<span class="status-dot offline"></span> DAEMON STANDBY';
        statusBadge.style.color = 'var(--muted, #a6b8c0)';
      }
    }
  }

  async function playPreset(presetId) {
    if (window.toast) window.toast(`Memainkan preset: ${presetId} ke speaker luar... 📢`);
    const btn = document.getElementById(`preset-btn-${presetId}`);
    if (btn) btn.classList.add('playing-pulse');

    try {
      // Direct local HTTP request (0ms latency)
      const res = await fetch(MEGAFON_HOST + `/api/megafon/play?preset=${encodeURIComponent(presetId)}`, {
        method: 'GET',
        signal: AbortSignal.timeout(3000)
      });
      if (res.ok) {
        setTimeout(checkStatus, 300);
      } else {
        throw new Error('HTTP ' + res.status);
      }
    } catch (err) {
      // Fallback to Native Android Interface if fetch fails
      if (window.AndroidEnergy && typeof window.AndroidEnergy.playMegafonPreset === 'function') {
        window.AndroidEnergy.playMegafonPreset(presetId);
      } else {
        if (window.toast) window.toast('Ralat: Daemon 8890 tidak bertindak balas');
      }
    } finally {
      setTimeout(() => { if (btn) btn.classList.remove('playing-pulse'); }, 2000);
    }
  }

  async function playDoa() {
    if (window.toast) window.toast('Memainkan Doa Naik Kenderaan dalam kabin... 🤲');
    try {
      await fetch(MEGAFON_HOST + '/api/doa/play', { method: 'GET', signal: AbortSignal.timeout(3000) });
    } catch (e) {
      if (window.AndroidEnergy && typeof window.AndroidEnergy.playDoa === 'function') {
        window.AndroidEnergy.playDoa();
      }
    }
  }

  async function startRecording() {
    if (isRecording) return;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioChunks = [];
      const mime = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : '';
      mediaRecorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
      
      mediaRecorder.ondataavailable = e => { if (e.data.size > 0) audioChunks.push(e.data); };
      mediaRecorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
        await processAndUploadRecording();
      };

      mediaRecorder.start();
      isRecording = true;
      recordingStartTime = Date.now();
      updateRecUI(true);

      recTimer = setInterval(() => {
        const sec = Math.floor((Date.now() - recordingStartTime) / 1000);
        const label = document.getElementById('rec-duration-label');
        if (label) label.textContent = `00:0${Math.min(9, sec)}`;
        if (sec >= 10) stopRecording();
      }, 200);

    } catch (err) {
      if (window.toast) window.toast('Sila benarkan akses mikrofon untuk rakaman.');
      console.error('Mic access error:', err);
    }
  }

  function stopRecording() {
    if (!isRecording) return;
    isRecording = false;
    clearInterval(recTimer);
    updateRecUI(false);
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      mediaRecorder.stop();
    }
  }

  function updateRecUI(recording) {
    const recBtn = document.getElementById('megafon-mic-btn');
    const recStatus = document.getElementById('megafon-mic-status');
    if (recBtn) {
      if (recording) {
        recBtn.classList.add('recording-active');
        if (recStatus) recStatus.textContent = 'SEDANG MERAKAM... (Lepas untuk siar)';
      } else {
        recBtn.classList.remove('recording-active');
        if (recStatus) recStatus.textContent = 'Tekan & Tahan untuk Bercakap';
      }
    }
  }

  async function processAndUploadRecording() {
    if (audioChunks.length === 0) return;
    const blob = new Blob(audioChunks, { type: audioChunks[0].type || 'audio/webm' });
    if (window.toast) window.toast('Menukar & Menyiarkan audio ke bumper... 🚀');

    try {
      // Convert to 16-bit PCM WAV for MegaphoneDaemon native tinyplay
      const wavBlob = await convertBlobToWav(blob);
      const res = await fetch(MEGAFON_HOST + '/api/megafon/upload', {
        method: 'POST',
        headers: { 'Content-Type': 'audio/wav' },
        body: wavBlob
      });
      if (res.ok) {
        if (window.toast) window.toast('Suara berjaya disiarkan ke speaker luar! 🔊');
        setTimeout(checkStatus, 500);
      } else {
        if (window.toast) window.toast('Gagal menyiarkan suara: HTTP ' + res.status);
      }
    } catch (err) {
      console.error('Upload error:', err);
      if (window.toast) window.toast('Ralat menyiarkan suara ke daemon.');
    }
  }

  // AudioContext Web Audio to 16-bit PCM WAV Encoder
  async function convertBlobToWav(blob) {
    const arrayBuffer = await blob.arrayBuffer();
    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    const audioBuffer = await audioCtx.decodeAudioData(arrayBuffer);
    
    const numChannels = 1; // Mono for exterior speaker
    const sampleRate = Math.min(44100, audioBuffer.sampleRate);
    const offlineCtx = new OfflineAudioContext(numChannels, Math.floor(audioBuffer.duration * sampleRate), sampleRate);
    const source = offlineCtx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(offlineCtx.destination);
    source.start();
    const renderedBuffer = await offlineCtx.startRendering();

    const pcmData = renderedBuffer.getChannelData(0);
    const wavBytes = encodeWavPcm16(pcmData, sampleRate);
    return new Blob([wavBytes], { type: 'audio/wav' });
  }

  function encodeWavPcm16(samples, sampleRate) {
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);
    function writeString(offset, string) {
      for (let i = 0; i < string.length; i++) view.setUint8(offset + i, string.charCodeAt(i));
    }
    writeString(0, 'RIFF');
    view.setUint32(4, 36 + samples.length * 2, true);
    writeString(8, 'WAVE');
    writeString(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true); // PCM
    view.setUint16(22, 1, true); // Mono
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true); // Byte rate
    view.setUint16(32, 2, true); // Block align
    view.setUint16(34, 16, true); // 16 bits
    writeString(36, 'data');
    view.setUint32(40, samples.length * 2, true);
    let offset = 44;
    for (let i = 0; i < samples.length; i++, offset += 2) {
      const s = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
    }
    return buffer;
  }

  function render() {
    return `
      <div class="megafon-dashboard">
        <!-- Status & Hardware Card -->
        <section class="card megafon-header-card">
          <div class="megafon-header-info">
            <div class="megafon-title-row">
              <span class="megafon-icon">📢</span>
              <div>
                <h2>Pusat Kawalan Speaker Luar Bumper</h2>
                <p>Kawalan pembesar suara luaran Proton e.MAS 5 (ALSA <code>pcmC0D29p</code>) dengan latensi 0ms terus dari IHU.</p>
              </div>
            </div>
            <div id="megafon-status-badge" class="megafon-status-badge">
              <span class="status-dot online"></span> AKTIF (0ms Tempatan)
            </div>
          </div>
        </section>

        <!-- Presets Grid -->
        <section class="card megafon-presets-card">
          <div class="card-head">
            <h3>Preset Suara Segera (1-Tap Exterior Play)</h3>
            <span class="badge">4 SLOT SIAP SEDIA</span>
          </div>
          <div class="megafon-grid">
            ${PRESETS.map(p => `
              <button type="button" id="preset-btn-${p.id}" class="megafon-tile" onclick="window.MegafonUI.playPreset('${p.id}')">
                <span class="tile-icon">${p.icon}</span>
                <strong class="tile-title">${p.name}</strong>
                <small class="tile-desc">${p.desc}</small>
                <span class="tile-play-indicator">▶ SIAR KE BUMPER</span>
              </button>
            `).join('')}
          </div>
        </section>

        <!-- Live Mic & Cabin Prayer -->
        <div class="megafon-lower-grid">
          <!-- Push to Talk Mic -->
          <section class="card megafon-mic-card">
            <div class="card-head">
              <h3>Mikrofon Langsung (Push-to-Talk)</h3>
              <span id="rec-duration-label" class="badge">00:00</span>
            </div>
            <p>Tekan dan tahan butang mikrofon untuk merakam suara, lepas butang untuk menyiarkan terus ke speaker bumper luar.</p>
            <div class="mic-button-wrap">
              <button type="button" id="megafon-mic-btn" class="megafon-ptt-btn"
                onmousedown="window.MegafonUI.startRecording()"
                onmouseup="window.MegafonUI.stopRecording()"
                ontouchstart="window.MegafonUI.startRecording()"
                ontouchend="window.MegafonUI.stopRecording()">
                🎙️
              </button>
              <div id="megafon-mic-status" class="mic-status-text">Tekan & Tahan untuk Bercakap</div>
            </div>
          </section>

          <!-- Cabin Prayer & Special Audio -->
          <section class="card megafon-cabin-card">
            <div class="card-head">
              <h3>Audio Kabin & Keselamatan</h3>
              <span class="badge">DALAM KABIN</span>
            </div>
            <p>Mainkan doa safar atau audio keselamatan terus melalui pembesar suara kabin dalaman kereta.</p>
            <div class="cabin-actions">
              <button type="button" class="primary cabin-btn" onclick="window.MegafonUI.playDoa()">
                🤲 Doa Naik Kenderaan (Kabin)
              </button>
            </div>
            <div class="megafon-spec-hint">
              <strong>Info Perkakasan:</strong>
              <span>Audio Routing: ALSA Hardware Bus (0ms)</span>
              <span>Daemon Bridge: Port 8890 Localhost</span>
            </div>
          </section>
        </div>
      </div>
    `;
  }

  function mount() {
    checkStatus();
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = setInterval(checkStatus, 3000);
  }

  function unmount() {
    if (pollTimer) clearInterval(pollTimer);
    if (recTimer) clearInterval(recTimer);
    pollTimer = null;
  }

  return {
    render,
    mount,
    unmount,
    playPreset,
    playDoa,
    startRecording,
    stopRecording
  };
})();
