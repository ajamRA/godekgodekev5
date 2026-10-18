(function (root) {
  'use strict';
  function createController(getBridge, changed) {
    var state = { presets: [], selected: '', loading: false, loaded: false,
      pending: '', previewing: '', checking: '', active: null, desired: null, syncRequired: null,
      observedAt: null, dirty: false, message: 'Muatkan senarai nada yang tersedia dalam IHU.' };
    var sequence = 0;
    function update(message) { if (message) state.message = message; changed(state); }
    function valid(key) { return typeof key === 'string' && /^[a-z0-9_]{1,32}$/.test(key) && key !== 'active'; }
    function available(key) { return state.presets.some(function (p) { return p.key === key; }); }
    function busy() { return state.loading || !!state.pending || !!state.previewing || !!state.checking; }
    function errorMessage(code) {
      var messages = {
        KNOWN_GOOD_BASELINE_REQUIRED: 'Persediaan nada asal belum lengkap. Sila semak pemasangan.',
        PRESET_MISSING_OR_SYMLINK: 'Fail nada tidak tersedia. Muat semula senarai.',
        LOCK_INSTALL_BUSY: 'Penukaran nada sedang digunakan. Cuba semula sebentar lagi.',
        LOCK_REQUEST_BUSY: 'Permintaan terdahulu masih diproses.',
        DAEMON_BUSY: 'Sambungan IHU sedang sibuk. Cuba semula sebentar lagi.',
        RESULT_UNKNOWN_TIMEOUT: 'Tiada pengesahan diterima. Nada mungkin sudah disimpan; semak sebelum mencuba lagi.',
        RESULT_UNKNOWN_CONNECTION_ERROR: 'Sambungan terputus. Keputusan penukaran belum disahkan.',
        INVALID_WAV_FORMAT_NEEDS_48KHZ_STEREO_PCM16: 'Format fail nada tidak serasi.',
        PREVIEW_UNAVAILABLE: 'Pratonton tidak dapat dimainkan.',
        PREVIEW_FAILED: 'Pratonton tidak dapat dimainkan.'
      };
      return messages[code] || 'Permintaan tidak berjaya. Cuba muat semula senarai nada.';
    }
    var api = {
      state: state,
      refresh: function () {
        if (busy()) return false;
        var bridge = getBridge();
        state.loaded = true;
        if (!bridge || typeof bridge.listLockSounds !== 'function') {
          update('Pemilih nada hanya tersedia pada skrin IHU.'); return false;
        }
        state.loading = true; update('Memuatkan nada dalam IHU…');
        try { bridge.listLockSounds(); }
        catch (error) { state.loading = false; update('Sambungan IHU belum tersedia.'); }
        return state.loading;
      },
      receivePresets: function (reply) {
        if (!state.loading) return;
        state.loading = false;
        if (!reply || reply.ok !== true || !Array.isArray(reply.presets)) {
          update(errorMessage(reply && reply.error)); return;
        }
        var seen = {};
        state.presets = reply.presets.filter(function (p) {
          if (!p || !valid(p.key) || seen[p.key]) return false;
          seen[p.key] = true; return true;
        }).slice(0, 64);
        if (!available(state.selected)) state.selected = state.presets.length ? state.presets[0].key : '';
        update(state.presets.length ? 'Pilih nada, dengar pratonton, kemudian simpan.'
          : 'Tiada preset nada dalam IHU. Sediakan fail nada sebelum penggunaan offline.');
        api.checkStatus();
      },
      select: function (key) { if (!busy() && available(key)) { state.selected = key; state.dirty = true; update(); } },
      checkStatus: function () {
        if (busy()) return false;
        var bridge = getBridge();
        if (!bridge || typeof bridge.getLockSoundStatus !== 'function') return false;
        state.checking = 's' + (++sequence); update();
        try { bridge.getLockSoundStatus(state.checking); }
        catch (_) { api.receiveStatus(state.checking, {ok:false}); }
        return !!state.checking;
      },
      receiveStatus: function (id, reply) {
        if (!state.checking || id !== state.checking) return;
        state.checking = ''; state.active = null; state.desired = null; state.syncRequired = null; state.observedAt = null;
        if (!reply || reply.ok !== true || !Object.prototype.hasOwnProperty.call(reply, 'active_sound')
            || !Object.prototype.hasOwnProperty.call(reply, 'sync_required')
            || !(reply.active_sound === null || valid(reply.active_sound))
            || !(reply.sync_required === null || typeof reply.sync_required === 'boolean')) {
          update(reply && reply.error === 'STATUS_UNSUPPORTED' ? 'Daemon lama: status aktif belum tersedia. Kemas kini daemon untuk semakan WAV.'
            : 'Status fail aktif tidak dapat disahkan. Semak sambungan dan cuba Semak status.'); return;
        }
        state.active = valid(reply.active_sound) ? reply.active_sound : null;
        state.desired = valid(reply.desired_sound) ? reply.desired_sound : null;
        state.syncRequired = reply.sync_required; state.observedAt = Date.now();
        if (!state.dirty) {
          var key = state.syncRequired === true ? state.desired : state.active;
          if (available(key)) state.selected = key;
        }
        update(state.syncRequired === true ? 'WAV kereta berbeza daripada nada tersimpan. Pilih nada dan tekan Sync to Car.'
          : state.active ? 'Fail aktif: ' + state.active + '. Bunyi OEM masih perlu diuji ketika mengunci.'
          : 'Fail diperiksa; nama nada tidak dapat dikenal pasti dengan pasti.');
      },
      apply: function () {
        if (busy() || !available(state.selected)) return false;
        var bridge = getBridge();
        if (!bridge || typeof bridge.applyLockSoundDirect !== 'function') { update('Sambungan IHU belum tersedia.'); return false; }
        state.pending = state.selected; update('Menyimpan nada…');
        try { bridge.applyLockSoundDirect(state.pending); }
        catch (error) { state.pending = ''; update('Keputusan penukaran belum disahkan.'); }
        return !!state.pending;
      },
      receiveResult: function (ok, key, reply) {
        if (!state.pending || key !== state.pending) return;
        state.pending = '';
        if (ok === true && reply && reply.ok === true && reply.file_committed === true && reply.sound === key) {
          state.dirty = false;
          update('Nada disimpan. Uji bunyi ketika kenderaan dikunci.');
        } else { update(errorMessage(reply && reply.error)); }
        state.active = null; state.observedAt = null; state.syncRequired = null;
        api.checkStatus();
      },
      preview: function () {
        if (busy() || !available(state.selected)) return false;
        var bridge = getBridge();
        if (!bridge || typeof bridge.playAudioPreview !== 'function') { update('Pratonton belum tersedia.'); return false; }
        state.previewing = state.selected; update('Memainkan pratonton dalam kabin…');
        try { bridge.playAudioPreview(state.previewing); }
        catch (error) { state.previewing = ''; update('Pratonton tidak dapat dimainkan.'); }
        return !!state.previewing;
      },
      receivePreview: function (ok, key, code) {
        if (!state.previewing || key !== state.previewing) return;
        state.previewing = ''; update(ok === true ? 'Pratonton selesai. Tekan Simpan nada untuk memilihnya.' : errorMessage(code));
      },
      invalidate: function () {
        state.loading = false; state.loaded = false; state.checking = '';
        state.active = null; state.desired = null; state.syncRequired = null; state.observedAt = null;
        state.previewing = ''; state.presets = [];
        if (state.pending) { state.pending = ''; update('Keputusan penukaran belum disahkan. Semak status sebelum mencuba lagi.'); }
        else update('Sambungan berubah. Muatkan semula status daripada kereta.');
      }
    };
    return api;
  }
  if (typeof module === 'object' && module.exports) { module.exports = createController; return; }
  var labels = { original: 'Nada asal', mario: 'Mario', quack: 'Quack', animewow: 'Anime Wow',
    tamatlah: 'Tamatlah', ack: 'ACK', fah: 'Fah', gopgop: 'Gop Gop', guru: 'Guru' };
  var controller = createController(function () { return root.AndroidEnergy || root.Android || root.CompanionBridge; }, function (state) {
    renderPanel(state); watchdog();
  });
  var timer = null;
  function renderPanel(state) {
    var panel = root.document.getElementById('lock-sound-panel');
    if (!panel) return;
    panel.innerHTML = '<h2>Nada kunci</h2><p>Pilih daripada fail dalam IHU. Pratonton dimainkan pada ' + (root.CompanionBridge ? 'telefon.' : 'kabin.') + '</p>'
      + '<div class="lock-active-row"><span>WAV aktif di kereta<strong data-lock-active></strong></span>'
      + '<span class="lock-sync-badge" data-lock-sync></span></div>'
      + '<label for="lock-sound-select">Pilihan nada untuk disimpan</label><select id="lock-sound-select"></select>'
      + '<div class="lock-sound-actions"><button type="button" class="secondary" data-lock-action="preview">Dengar</button>'
      + '<button type="button" class="primary" data-lock-action="apply">Sync to Car</button>'
      + '<button type="button" class="secondary" data-lock-action="checkStatus">Semak status</button>'
      + '<button type="button" class="secondary" data-lock-action="refresh">Muat semula</button></div>'
      + '<p class="lock-sound-status" role="status" aria-live="polite"></p>';
    var select = panel.querySelector('select');
    state.presets.forEach(function (p) {
      var option = root.document.createElement('option'); option.value = p.key;
      option.textContent = labels[p.key] || p.key.replace(/_/g, ' '); select.appendChild(option);
    });
    var busy = state.loading || !!state.pending || !!state.previewing || !!state.checking;
    var fresh = state.observedAt !== null && Date.now() - state.observedAt < 15000;
    panel.querySelector('[data-lock-active]').textContent = fresh && state.active ? labels[state.active] || state.active : 'Belum disahkan';
    panel.querySelector('[data-lock-sync]').textContent = state.checking ? 'Memeriksa WAV…' : !fresh ? 'Status perlu disemak'
      : state.syncRequired === true ? 'Perlu sync' : state.syncRequired === false ? 'WAV sepadan' : 'Nada tersimpan belum diketahui';
    select.value = state.selected; select.disabled = busy || !state.presets.length;
    select.onchange = function () { controller.select(select.value); };
    Array.prototype.forEach.call(panel.querySelectorAll('[data-lock-action]'), function (button) {
      var action = button.getAttribute('data-lock-action');
      button.disabled = busy || (['apply','preview'].indexOf(action) !== -1 && !state.selected);
      button.onclick = function () { controller[action](); };
    });
    panel.querySelector('.lock-sound-status').textContent = state.message;
  }
  // Watchdogs release the UI only; they never retry an uncertain file commit.
  function watchdog() {
    root.clearTimeout(timer);
    if (controller.state.loading || controller.state.pending || controller.state.previewing || controller.state.checking) {
      timer = root.setTimeout(function () {
        if (controller.state.loading) controller.receivePresets({ ok: false, error: 'RESULT_UNKNOWN_TIMEOUT' });
        if (controller.state.pending) controller.receiveResult(false, controller.state.pending, { error: 'RESULT_UNKNOWN_TIMEOUT' });
        if (controller.state.previewing) {
          var bridge = root.AndroidEnergy || root.Android || root.CompanionBridge;
          if (bridge && bridge.stopLockSoundPreview) bridge.stopLockSoundPreview();
          controller.receivePreview(false, controller.state.previewing, 'PREVIEW_UNAVAILABLE');
        }
        if (controller.state.checking) controller.receiveStatus(controller.state.checking, {ok:false});
      }, 20000);
    }
  }
  root.onLockSoundPresets = function (reply) { controller.receivePresets(reply); };
  root.onLockSoundResult = function (ok, key, reply) { controller.receiveResult(ok, key, reply); };
  root.onLockSoundPreviewResult = function (ok, key, error) { controller.receivePreview(ok, key, error); };
  root.onLockSoundStatus = function (id, reply) { controller.receiveStatus(id, reply); };
  var statusPoller = root.CompanionClient.createPoller(function () {
    if (root.document.getElementById('lock-sound-panel')) controller.checkStatus();
  }, {interval:10000});
  function pause() { statusPoller.pause(); root.clearTimeout(timer); controller.invalidate(); if (root.CompanionBridge) root.CompanionBridge.stopPreview(); }
  function resume() {
    if (root.CompanionPaused || root.document.hidden || root.location.hash !== '#controls') return;
    if (!root.CompanionPaused && !controller.state.loaded) controller.refresh();
    statusPoller.resume();
  }
  root.addEventListener('hashchange', function () { statusPoller.pause(); resume(); });
  root.document.addEventListener('visibilitychange', function () { if (root.document.hidden) pause(); else resume(); });
  root.LockSoundUI = { mount: function (panel) {
    if (!panel) return;
    renderPanel(controller.state);
    if (!root.CompanionPaused && !controller.state.loaded) controller.refresh();
    resume();
  }, pause:pause, resume:resume, invalidate:function () { pause(); resume(); } };
}(typeof window !== 'undefined' ? window : this));
