(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.SafeVehicle = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';
  const validRegen = value => Number.isInteger(value) && value >= 1 && value <= 3;
  const clock = () => typeof performance !== 'undefined' ? performance.now() : Date.now();
  function createClient(host, now = clock) {
    const transport = nativeTransport(host);
    return {
      async request(method, path, body = '') {
        const started = now();
        try {
          const response = await transport(method, path, body);
          const data = response.body;
          if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('INVALID_RESPONSE');
          const ok = response.status >= 200 && response.status < 300 && data.ok === true;
          const age = data.age_ms;
          return { ...data, ok, http_status: response.status,
            error: ok ? '' : (typeof data.error === 'string' && data.error || 'HTTP_OR_DAEMON_REJECTED'),
            // Charge the full request duration conservatively; cached backend age is never reset.
            _observedAt: ok && typeof age === 'number' && Number.isFinite(age) && age >= 0
              ? started - age : null };
        } catch (error) {
          return { ok: false, http_status: 0, _observedAt: null, error: error.message || 'REQUEST_UNAVAILABLE' };
        }
      }
    };
  }
  function nativeTransport(host) {
    const pending = new Map();
    let sequence = 0;
    host.onVehicleResponse = function (id, response) {
      const entry = pending.get(id);
      if (!entry) return; // Ignore late/unknown callbacks; never retry.
      pending.delete(id); clearTimeout(entry.timer);
      if (!response || !Number.isInteger(response.status)) return entry.reject(new Error('INVALID_RESPONSE'));
      entry.resolve(response);
    };
    const bridge = () => host.AndroidEnergy || host.Android;
    const send = (method, path, body) => new Promise((resolve, reject) => {
      const native = bridge();
      if (!native || typeof native.requestVehicle !== 'function') return reject(new Error('NATIVE_BRIDGE_REQUIRED'));
      if (pending.size >= 4) return reject(new Error('BUSY'));
      const id = 'v' + (++sequence);
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('TIMEOUT')); }, 8000);
      pending.set(id, { resolve, reject, timer });
      try { native.requestVehicle(id, method, path, body || ''); }
      catch (_) { clearTimeout(timer); pending.delete(id); reject(new Error('BRIDGE_ERROR')); }
    });
    send.available = () => !!(bridge() && typeof bridge().requestVehicle === 'function');
    return send;
  }
  function createController(transport, onChange = () => {}, now = Date.now) {
    const state = { observed: null, observedAt: null, command: 'idle', requested: null, available: false };
    let polling = false;
    const notify = () => onChange({ ...state, observed: state.observed && { ...state.observed } });
    const observe = data => {
      const current = data && data.current;
      state.observed = current ? {
        regen: validRegen(current.regen_level) ? current.regen_level : null,
        mode: Number.isInteger(current.drive_mode) && current.drive_mode >= 1 && current.drive_mode <= 4 ? current.drive_mode : null
      } : null;
      state.observedAt = state.observed ? now() : null;
    };
    return {
      state,
      async poll() {
        if (polling) return;
        polling = true;
        try {
          const response = await transport('GET', '/api/drive_mode', '');
          const data = response.body;
          if (response.status !== 200 || !data || data.ok !== true || data.candidate_controls_enabled !== false
              || data.confirmed !== false || data.dispatch_requested !== false || !data.current
              || !data.lock || data.lock.enabled !== false) throw new Error('INCOMPATIBLE_DAEMON');
          state.available = true; observe(response.body);
        } catch (_) { state.available = false; state.observed = null; state.observedAt = null; }
        finally { polling = false; notify(); }
      },
      async sendRegen(level) {
        if (!validRegen(level) || state.command === 'sending') return false;
        if (!state.available || state.observedAt === null || now() - state.observedAt > 5000) {
          state.command = 'unavailable'; notify(); return false;
        }
        state.requested = level; state.command = 'sending'; notify();
        try {
          const response = await transport('POST', '/api/drive_mode', JSON.stringify({ regen: level }));
          if (response.status === 200 && response.body && response.body.ok === true
              && response.body.confirmed === false && response.body.dispatch_requested === true
              && response.body.candidate_controls_enabled === false) {
            state.command = 'dispatched'; // No optimistic observed-state update, even if values match.
            notify(); return true;
          }
          state.command = response.status >= 400 ? 'failed' : 'unknown';
        } catch (_) { state.command = 'unknown'; }
        notify(); return false;
      }
    };
  }
  const messages = {
    idle: 'Belum ada arahan dihantar.',
    sending: 'Menghantar arahan…',
    dispatched: 'Arahan dihantar. Perubahan pada kereta belum disahkan.',
    failed: 'Arahan ditolak atau gagal. Pilihan pada kereta belum disahkan.',
    unknown: 'Respons tidak diterima. Arahan mungkin telah dihantar; tiada cubaan semula automatik.',
    unavailable: 'Kawalan tidak tersedia. Sambungan aplikasi ke kereta diperlukan.'
  };
  function renderDrive(snapshot) {
    const observed = snapshot.observed && snapshot.observed.regen;
    const labels = { 1: 'Rendah', 2: 'Sederhana', 3: 'Tinggi' };
    const blocked = !snapshot.available || snapshot.command === 'sending';
    return `<div class="detail-grid">
      <section class="card safe-drive-card">
        <h2>Energy Recovery</h2>
        <p>Bacaan terakhir: <strong>${labels[observed] || 'Tidak tersedia'}</strong></p>
        <p class="hint">Bacaan ini belum mengesahkan pelaksanaan arahan terbaru.</p>
        <div class="safe-regen-options">${[1,2,3].map(level => `<button type="button" data-action="set-regen" data-regen="${level}"
          aria-pressed="${observed === level}" ${blocked ? 'disabled' : ''}>${labels[level]}</button>`).join('')}</div>
        <p role="status" aria-live="polite">${messages[snapshot.command] || messages.idle}</p>
        ${!snapshot.available ? '<p class="hint">Pratonton web tidak menyambung terus ke kereta. Gunakan aplikasi Android yang serasi.</p>' : ''}
      </section>
      <section class="card safe-drive-card"><h2>Mod pemanduan</h2>
        <p>Kawalan belum tersedia kerana pemetaan belum disahkan pada kereta.</p>
        <div class="safe-regen-options">${['Comfort','Sport','Eco','Eco Plus'].map(name => `<button type="button" disabled>${name}</button>`).join('')}</div>
      </section>
      <section class="card safe-drive-card"><h2>Tetapan brek</h2>
        <p>Comfort Brake dan rasa pedal brek belum tersedia. Gunakan tetapan asal kereta.</p>
        <button type="button" disabled>Comfort Brake</button> <button type="button" disabled>Rasa pedal brek</button>
      </section>
    </div>`;
  }
  return { nativeTransport, createClient, clock, createController, renderDrive, validRegen, messages };
});
