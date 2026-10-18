(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else { root.CompanionClient = api; api.mount(root); }
}(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';
  var reads = ['/api/energy', '/api/status', '/api/locksound/status', '/api/locksound/list'];
  function createClient(env) {
    var queue = [], active = false, stopped = false, currentRead = null;
    var connected = false;
    var timeoutMs = env.timeoutMs || 4500;
    function once(url, method, body, read) {
      var controller = new AbortController(), timer;
      if (read) currentRead = controller;
      var deadline = new Promise(function (_, reject) {
        timer = setTimeout(function () { controller.abort(); reject(new Error('TIMEOUT')); }, read ? timeoutMs : 12000);
      });
      var operation = (async function () {
        var response = await env.fetch(url, { method: method, credentials: 'same-origin',
          headers: body ? { 'Content-Type': 'application/json' } : {}, body: body || undefined,
          cache: 'no-store', signal: controller.signal, redirect: 'error' });
        var text = await response.text();
        if (text.length > 32768) throw new Error('RESPONSE_TOO_LARGE');
        var data;
        try { data = JSON.parse(text); } catch (_) { throw new Error('INVALID_JSON_HTTP_' + response.status); }
        if (!data || Array.isArray(data) || typeof data !== 'object') throw new Error('INVALID_RESPONSE');
        return { status: response.status, body: data };
      }());
      return Promise.race([operation, deadline]).finally(function () {
        clearTimeout(timer); if (currentRead === controller) currentRead = null;
      });
    }
    async function drain() {
      if (active || !queue.length) return;
      active = true;
      var job = queue.shift();
      try {
        if (stopped) throw new Error('PAUSED');
        var read = job.method === 'GET' && reads.indexOf(job.path) !== -1;
        var result;
        for (var attempt = 0; attempt < (read ? 2 : 1); attempt++) {
          try {
            result = await once(job.path, job.method, job.body, read);
            if (read && attempt === 0 && [429, 502, 503, 504].indexOf(result.status) !== -1) continue;
            break;
          } catch (error) { if (!read || attempt || stopped) throw error; }
        }
        job.resolve(result);
      } catch (error) { job.reject(error); }
      finally { active = false; drain(); }
    }
    function request(method, path, body) {
      if (!connected) return Promise.reject(new Error('CAR_OFFLINE'));
      if (stopped) return Promise.reject(new Error('PAUSED'));
      if (queue.length + (active ? 1 : 0) >= 4) return Promise.reject(new Error('BUSY'));
      return new Promise(function (resolve, reject) {
        queue.push({ method: method, path: path, body: body || '', resolve: resolve, reject: reject }); drain();
      });
    }
    request.available = function () { return connected && !stopped; };
    return {
      request: request,
      async connect(ip) {
        if (active || queue.length) throw new Error('BUSY');
        connected = false;
        var response = await once('/companion/connect', 'POST', JSON.stringify({ ip: ip || '', last:env.lastIp || '' }), true);
        if (response.status !== 200 || response.body.ok !== true) throw new Error(response.body.error || 'CAR_OFFLINE');
        connected = true; return response.body;
      },
      pause: function () {
        stopped = true;
        if (currentRead) currentRead.abort();
        queue.splice(0).forEach(function (job) { job.reject(new Error('PAUSED')); });
      },
      resume: function () { stopped = false; },
      disconnect: function () { connected = false; }
    };
  }
  // Completion-driven polling: one cycle at a time; pause invalidates late samples.
  function createPoller(run, options) {
    options = options || {};
    var timer = null, running = false, enabled = false, generation = 0, failures = 0;
    var interval = options.interval || 1200;
    var schedule = options.setTimeout || setTimeout, cancel = options.clearTimeout || clearTimeout;
    async function tick() {
      if (!enabled || running) return;
      running = true; var epoch = generation;
      try { failures = await run(function () { return enabled && epoch === generation; }) === false ? failures + 1 : 0; }
      catch (_) { failures++; }
      finally {
        running = false;
        if (enabled) timer = schedule(tick, epoch !== generation ? 0 : Math.min(15000, interval * Math.pow(2, failures)));
      }
    }
    return {
      resume: function () { if (enabled) return; enabled = true; generation++; tick(); },
      pause: function () { enabled = false; generation++; cancel(timer); timer = null; },
      refresh: function () { failures = 0; cancel(timer); if (enabled) tick(); }
    };
  }
  function mount(root) {
    var native = root.AndroidEnergy || root.Android;
    var panel = root.document.getElementById('connection-panel');
    if (!panel) return;
    var text = panel.querySelector('[data-connection-message]');
    var badge = panel.querySelector('[data-connection-state]');
    var form = panel.querySelector('form');
    var remembered = '';
    try { remembered = root.localStorage.getItem('emas-car-ip') || ''; } catch (_) {}
    var client = !native && /^https?:$/.test(root.location.protocol) ? createClient({ fetch: root.fetch.bind(root),lastIp:remembered }) : null;
    form.querySelector('input').value = remembered;
    if (native) form.hidden = true;
    if (native) panel.querySelector('#car-ip-help').hidden = true;
    function observe(ok, error) {
      badge.textContent = ok ? 'TERSAMBUNG' : 'OFFLINE';
      panel.dataset.connection = ok ? 'online' : 'offline';
      text.textContent = ok ? (native ? 'Sambungan tempatan IHU aktif.' : 'Gateway tersambung ke kereta.')
        : (error || 'Kereta offline / mungkin dalam sleep mode. Semak Wi-Fi dan hidupkan IHU.');
    }
    root.CompanionConnection = { transport: client && client.request, client: client, observe: observe };
    if (!native) {
      badge.textContent = 'BELUM DISAMBUNG';
      text.textContent = 'Sambung melalui gateway pada komputer. Telefon dan komputer perlu berada pada Wi-Fi yang sama.';
    }
    async function connect(ip) {
      if (!client) { observe(false, 'Buka halaman melalui gateway Web Companion.'); return; }
      var buttons = Array.from(form.querySelectorAll('button'));
      buttons.forEach(function (b) { b.disabled = true; }); badge.textContent = 'MENCARI…';
      try {
        var result = await client.connect(ip);
        try { root.localStorage.setItem('emas-car-ip', result.target); } catch (_) {}
        observe(true); text.textContent = 'Kereta: ' + result.target + ' · ' + result.mode;
        if (root.LockSoundUI) root.LockSoundUI.invalidate();
        root.dispatchEvent(new CustomEvent('companion-connected'));
      } catch (error) {
        observe(false, error.message === 'INVALID_PRIVATE_IP' ? 'Masukkan IP rangkaian tempatan yang sah, contohnya 192.168.1.8.'
          : 'Kereta tidak dapat dicapai. Semak IP atau sambungan ADB pada gateway.');
      } finally { buttons.forEach(function (b) { b.disabled = false; }); }
    }
    form.onsubmit = function (event) { event.preventDefault(); connect(form.querySelector('input').value.trim()); };
    form.querySelector('[data-detect]').onclick = function () { connect(''); };
    if (client) {
      var preview = null, previewTimer = null;
      function stopPreview() { clearTimeout(previewTimer); if (preview) { preview.pause(); preview.src = ''; preview = null; } }
      root.CompanionBridge = {
        listLockSounds: async function () {
          try { var r = await client.request('GET', '/api/locksound/list'); root.onLockSoundPresets(r.status === 200 ? r.body : { ok:false, error:r.body.error }); }
          catch (_) { root.onLockSoundPresets({ok:false,error:'RESULT_UNKNOWN_CONNECTION_ERROR'}); }
        },
        getLockSoundStatus: async function (id) {
          try { var r = await client.request('GET', '/api/locksound/status'); root.onLockSoundStatus(id, r.status === 200 ? r.body : {ok:false,error:r.status === 404 ? 'STATUS_UNSUPPORTED' : r.body.error}); }
          catch (_) { root.onLockSoundStatus(id, {ok:false,error:'RESULT_UNKNOWN_CONNECTION_ERROR'}); }
        },
        applyLockSoundDirect: async function (key) {
          try { var r = await client.request('POST', '/api/locksound/apply', JSON.stringify({sound:key})); root.onLockSoundResult(r.status === 200, key, r.body); }
          catch (_) { root.onLockSoundResult(false, key, {error:'RESULT_UNKNOWN_CONNECTION_ERROR'}); }
        },
        playAudioPreview: function (key) {
          if (!/^[a-z0-9_]{1,32}$/.test(key)) return;
          stopPreview(); var audio = preview = new root.Audio('/api/locksound/preview?sound=' + key);
          function finish(ok) { if (preview !== audio) return; stopPreview(); root.onLockSoundPreviewResult(ok, key, 'PREVIEW_UNAVAILABLE'); }
          audio.onended = function () { finish(true); }; audio.onerror = function () { finish(false); };
          previewTimer = setTimeout(function () { finish(false); }, 12000);
          audio.play().catch(function () { finish(false); });
        },
        stopPreview: stopPreview,
        stopLockSoundPreview: stopPreview
      };
    }
    root.document.addEventListener('click', function (event) {
      var button = event.target.closest('button');
      if (button && !button.disabled && root.navigator.vibrate) root.navigator.vibrate(12);
    });
  }
  return { createClient: createClient, createPoller: createPoller, mount: mount };
}));
