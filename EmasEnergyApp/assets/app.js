const icons = {
  overview: '<path d="M12 14l3.5-3.5"/><path d="M3.34 16.5A9.5 9.5 0 1 1 20.66 16.5"/><circle cx="12" cy="14" r="2" fill="currentColor"/><path d="M6 14h1.5 M16.5 14H18 M8 8.5l1 1 M15 9.5l1-1 M12 5v1.5"/>',
  battery: '<rect x="2" y="7" width="18" height="12" rx="2.5"/><path d="M22 11v4 M6 7V5 M16 7V5"/><path d="M11.5 9.5l-2.5 4h3l-1 4 4.5-5.5h-3l1.5-2.5z" fill="currentColor" stroke="none"/>',
  power: '<circle cx="12" cy="12" r="4.5"/><path d="M12 2v5.5 M12 16.5V22 M2 12h5.5 M16.5 12H22 M7 7l2 2 M15 15l2 2 M17 7l-2 2 M9 15l-2 2"/><circle cx="12" cy="12" r="1.5" fill="currentColor"/>',
  trip: '<path d="M21 12a9 9 0 0 0-15.3-6.36L3 8 M3 3v5h5 M3 12a9 9 0 0 0 15.3 6.36L21 16 M21 21v-5h-5 M12 8.5v7 M8.5 12h7"/>',
  climate: '<circle cx="12" cy="12" r="2.5" fill="currentColor"/><path d="M12 9.5C12 5 15.5 3 15.5 3S16 7 13.8 8.8 M14.5 12C19 12 21 15.5 21 15.5S17 16 15.2 13.8 M12 14.5C12 19 8.5 21 8.5 21S8 17 10.2 15.2 M9.5 12C5 12 3 8.5 3 8.5S7 8 8.8 10.2"/>',
  body: '<path d="M7 5.5C7 3.5 8.5 2 12 2s5 1.5 5 3.5v13c0 2-1.5 3.5-5 3.5s-5-1.5-5-3.5v-13z M8 7.5h8 M8 16.5h8 M7 8l-2.5 1 M17 8l2.5 1 M9 11h6 M9 13.5h6"/>',
  controls: '<path d="M4 6h16 M4 18h16 M4 12h16"/><circle cx="8" cy="6" r="2.2" fill="currentColor"/><circle cx="16" cy="12" r="2.2" fill="currentColor"/><circle cx="10" cy="18" r="2.2" fill="currentColor"/>',
  camp: '<path d="M3 20L12 4l9 16H3z M12 4v16 M8.5 20l3.5-6 3.5 6"/>',
  vent: '<path d="M4 4h14a2 2 0 0 1 2 2v14H4V4z M4 10h16 M9 13v4 M12 13v4 M15 13v4 M4 7l3-3"/>',
  sound: '<path d="M11 5L6 9H2v6h4l5 4V5z M15.54 8.46a5 5 0 0 1 0 7.07 M18.8 5.2a9.5 9.5 0 0 1 0 13.6"/>',
  power_btn: '<path d="M12 2v9 M18.36 6.64a9 9 0 1 1-12.73 0"/>',
  air_fresh: '<path d="M3 12h14 M13 8l4 4-4 4 M21 5v14"/>',
  air_recirc: '<path d="M4 12a8 8 0 0 1 14-5.3L20 8 M20 3v5h-5 M20 12a8 8 0 0 1-14 5.3L4 16 M4 21v-5h5"/>',
  defog: '<path d="M3 5c2 4 14 4 18 0v14c-4-4-16-4-18 0V5z M8 8c.5 1.5-.5 2.5 0 4 M12 8c.5 1.5-.5 2.5 0 4 M16 8c.5 1.5-.5 2.5 0 4"/>',
  drivemode: '<path d="M12 14l3.5-3.5"/><path d="M3.34 16.5A9.5 9.5 0 1 1 20.66 16.5"/><circle cx="12" cy="14" r="2" fill="currentColor"/><path d="M6 14h1.5 M16.5 14H18 M8 8.5l1 1 M15 9.5l1-1 M12 5v1.5"/>',
  megafon: '<path d="M11 5L6 9H2v6h4l5 4V5z"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/>'
};

const icon = (name) => `<svg class="icon automotive-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name] || icons.power}</svg>`;

// Vehicle access is exclusively through the reviewed native bridge. No browser HTTP fallback.
const vehicleTransport = window.CompanionConnection && window.CompanionConnection.transport || SafeVehicle.nativeTransport(window);
async function vehicleFetch(path, options = {}) {
  const method = options.method || 'GET';
  const isAvas = typeof path === 'string' && path.startsWith('/api/avas');
  const isBoot = typeof path === 'string' && (path.startsWith('/api/frunk') || path.startsWith('/api/trunk'));
  const isAllowedGet = method === 'GET' && ['/api/energy', '/api/status', '/api/drive_mode'].includes(path);
  const isWindow = method === 'GET' && ['/api/window?action=crack', '/api/window?action=close'].includes(path);
  if (!isAvas && !isAllowedGet && !isBoot && !isWindow) {
    throw new Error('Kawalan ini belum tersedia dalam calon ini.');
  }
  const body = options.body ? (typeof options.body === 'string' ? options.body : JSON.stringify(options.body)) : '';
  const response = await vehicleTransport(method, path, body);
  return { ok: response.status === 200 && response.body && response.body.ok !== false, status: response.status, json: async () => response.body };
}
function getApiBase() { return '/offline-preview'; } // Media preview only; never a vehicle address.
let batteryReading = { source: null, soc: null, voltage: null, current: null, observedAt: null };
let telemetryObservedAt = 0;
function canControlVehicle() { return vehicleTransport.available() && telemetryObservedAt > 0 && Date.now() - telemetryObservedAt <= 15000; }
function updateControlAvailability() {
  document.querySelectorAll('[data-action^="window-"]').forEach(button => { button.disabled = windowCommandBusy || !canControlVehicle(); });
}
function energyReadings() {
  const live = state.isLive;
  const soc = live && Number.isFinite(batteryReading.soc) ? batteryReading.soc : null;
  const voltage = live && Number.isFinite(batteryReading.voltage) ? batteryReading.voltage : null;
  const current = live && Number.isFinite(batteryReading.current) ? batteryReading.current : null;
  const power = voltage !== null && current !== null ? voltage * current / 1000 : null;
  const time = live && batteryReading.observedAt ? new Date(batteryReading.observedAt).toLocaleTimeString() : '—';
  return { live, soc, voltage, current, power, time,
    value: (n, digits = 1) => Number.isFinite(n) ? n.toFixed(digits) : '—' };
}
function overviewCockpitPage() {
  const d = energyReadings();
  const liveVehicle = state.vehicleTelemetryAvailable || state.isSimulation;
  const gear = (liveVehicle && state.gear ? state.gear : (state.isSimulation ? 'P' : '—')).toUpperCase();
  const gearName = ({P:'PARK',R:'REVERSE',N:'NEUTRAL',D:'DRIVE'})[gear] || (gear === '—' ? 'BELUM DIKETAHUI' : 'GEAR');
  const speed = liveVehicle && Number.isFinite(state.speed) ? state.speed : (state.isSimulation ? 0.0 : null);
  
  let motion = 'MENUNGGU DATA';
  if (speed !== null) {
    if (speed > 0.5) {
      motion = gear === 'R' ? 'SEDANG MENGUNDUR' : 'SEDANG MEMANDU';
    } else {
      motion = gear === 'P' ? 'DIPARKIR' : gear === 'D' ? 'SIAP SEDIA (DRIVE)' : gear === 'R' ? 'SIAP SEDIA (REVERSE)' : 'BERHENTI (NEUTRAL)';
    }
  }
  
  // Doors, Frunk & Trunk
  const doorItems = [
    ['Depan Kiri', 'fl', 'FL', false],
    ['Depan Kanan', 'fr', 'FR', false],
    ['Belakang Kiri', 'rl', 'RL', false],
    ['Belakang Kanan', 'rr', 'RR', false],
    ['Bonet Depan', 'frunk', 'FRUNK', true],
    ['Bonet Belakang', 'trunk', 'TRUNK', true]
  ];
  const knownDoorCount = Object.values(state.doorKnown || {}).filter(Boolean).length;
  const openDoors = knownDoorCount ? doorItems.filter(([,key]) => state.doorKnown[key] && state.doors[key] === true).length : null;
  const allDoorsClosed = knownDoorCount === doorItems.length && openDoors === 0;

  // Seatbelt & Passenger Occupancy Logic (Seat weight sensor + belt)
  let cabinPresence = 'Tiada data sensor';
  let cabinSub = 'Kehadiran penumpang belum diketahui';
  let cabinBadge = '';
  const sb = state.seatbelt || {};
  if (sb.passenger === 1) {
    cabinPresence = 'Penumpang Dikesan';
    cabinSub = 'Tali pinggang belum dipasang!';
    cabinBadge = '<span class="badge" style="background:rgba(239,68,68,0.25); color:#ef4444; border:1px solid #ef4444; font-size:10px;">AMARAN TALI PINGGANG</span>';
  } else if (sb.passenger === 0 && sb.driver === 0) {
    cabinPresence = 'Status tali pinggang';
    cabinSub = 'Tali pinggang dipasang selamat';
    cabinBadge = '<span class="badge" style="background:rgba(16,185,129,0.2); color:#10b981; font-size:10px;">SELAMAT</span>';
  } else if (sb.driver === 1) {
    cabinPresence = 'Pemandu';
    cabinSub = 'Sila pasang tali pinggang keledar';
    cabinBadge = '<span class="badge" style="background:rgba(245,158,11,0.25); color:#f59e0b; font-size:10px;">TALI PINGGANG</span>';
  }

  return `<section class="vehicle-status-board" aria-label="Status kenderaan">
    <article class="vehicle-state-primary">
      <span class="state-kicker">KEADAAN KENDERAAN</span>
      <strong>${motion}</strong>
      <div>
        <span class="gear-chip"><b>${gear}</b> ${gearName}</span>
        <span class="speed-chip">${speed === null ? '— km/j' : `${d.value(speed, 0)} km/j`}</span>
        <span class="speed-chip" style="color:var(--muted); font-size:12px;">${Number.isFinite(state.ambientTemp) && liveVehicle ? `${state.ambientTemp.toFixed(1)}°C Luar` : '— °C Luar'}</span>
      </div>
    </article>
    <article class="vehicle-status-tile cabin-door-card" style="display:flex; flex-direction:column; justify-content:center; gap:8px;">
      <div class="cabin-status-head">
        <div>
          <span>KEHADIRAN & KESELAMATAN KABIN</span>
          <div style="display:flex; align-items:center; gap:8px;">
            <strong>${cabinPresence}</strong>
            ${cabinBadge}
          </div>
          <small>${cabinSub}</small>
        </div>
      </div>
      <div class="cabin-belt-summary">
        <div class="belt-chip ${sb.driver === 0 ? 'belt-ok' : sb.driver === 1 ? 'belt-warn' : ''}">
          <span>TALI PINGGANG PEMANDU</span>
          <strong>${sb.driver === 0 ? '✓ DIPASANG' : sb.driver === 1 ? '⚠ SILA PASANG' : 'TIADA DATA'}</strong>
        </div>
        <div class="belt-chip ${sb.passenger === 0 ? 'belt-ok' : sb.passenger === 1 ? 'belt-warn' : ''}">
          <span>TALI PINGGANG PENUMPANG</span>
          <strong>${sb.passenger === 0 ? '✓ DIPASANG' : sb.passenger === 1 ? '⚠ SILA PASANG' : 'TIADA DATA'}</strong>
        </div>
      </div>
    </article>
    ${typeof powerGraphMarkup === 'function' ? powerGraphMarkup() : ''}
    <section class="tpms-panel door-monitor-panel" aria-label="Status bukaan pintu, frunk dan but">
      <div class="tpms-panel-head">
        <div>
          <span class="state-kicker">KAWALAN & SENSOR · e.MAS 5</span>
          <h2>Status Pintu, Frunk & But</h2>
        </div>
        <span class="tpms-source" style="font-weight:600; ${allDoorsClosed ? 'color:#10b981; background:rgba(16,185,129,0.12);' : 'color:#ef4444; background:rgba(239,68,68,0.18);'}">
          ${allDoorsClosed ? '✓ SEMUA TERTUTUP' : openDoors === null ? 'TIADA DATA PINTU' : openDoors > 0 ? `⚠ ${openDoors} TERBUKA` : 'SEBAHAGIAN SENSOR SAHAJA'}
        </span>
      </div>
      <div class="door-status-grid">
        ${doorItems.map(([label, key, code, isClickable]) => {
          const isFrunk = key === 'frunk';
          const isTrunk = key === 'trunk';
          const known = state.doorKnown && state.doorKnown[key] === true;
          const open = known && state.doors && state.doors[key] === true;
          const statusText = !known ? '—' : open ? 'TERBUKA' : 'TUTUP';
          const subText = isClickable
            ? (open ? (isTrunk ? '👆 Tekan Tutup' : 'Terbuka') : '👆 Tekan Buka')
            : (!known ? 'Sensor Menunggu' : open ? 'Pintu Renggang' : 'Rapat Selamat');
          const actionAttr = isFrunk ? 'data-action="open-frunk"' : isTrunk ? 'data-action="toggle-trunk"' : '';
          const canControl = isClickable && canControlVehicle() && known && !bootCommandBusy;
          const tag = isClickable ? 'button' : 'article';
          const actionRole = isClickable ? `type="button" ${canControl ? '' : 'disabled'}` : '';
          return `
            <${tag} class="tpms-reading door-status-tile ${isClickable ? 'clickable-boot-tile' : ''} ${known ? (open ? 'is-open' : 'is-closed') : 'is-unknown'}" ${actionAttr} ${actionRole}>
              <div style="display:flex; justify-content:space-between; align-items:center;">
                <span>${code} · ${label}</span>
                ${isClickable ? `<span class="boot-badge">${isFrunk ? 'BUKA' : (open ? 'TUTUP' : 'BUKA')}</span>` : ''}
              </div>
              <strong style="color:${open ? '#ef4444' : '#10b981'}; font-size:15px;">${statusText}</strong>
              <small style="color:${isClickable ? 'var(--accent, #6ee7b7)' : (open ? '#ff7b72' : '#8f9e94')}; font-weight:${isClickable ? '600' : 'normal'};">${subText}</small>
            </${tag}>
          `;
        }).join('')}
      </div>
    </section>
  </section>
  <section class="overview-energy-strip" aria-label="statustenaga">
    <div class="energy-strip-title"><span class="eyebrow">ENERGY</span><strong class="energy-wordmark"><b>status</b><span>tenaga</span></strong><a href="#battery">BUTIRAN BATERI ↗</a></div>
    <div class="energy-strip-metrics">
      <article><span>CAS BATERI</span><strong>${d.value(d.soc)}<small>%</small></strong></article>
      <article><span>VOLTAN PEK</span><strong>${d.value(d.voltage)}<small>V</small></strong></article>
      <article><span>ARUS PEK</span><strong>${d.value(d.current)}<small>A</small></strong></article>
      <article><span>KUASA PEK · ANGGARAN</span><strong>${d.value(d.power, 2)}<small>kW</small></strong></article>
    </div>
  </section>`;
}
function hvBatteryPage() {
  const d = energyReadings(), level = d.soc === null ? 0 : Math.max(0, Math.min(100, d.soc));
  const segments = Array.from({length:16}, (_, i) => `<i class="${d.soc !== null && i < Math.ceil(level / 100 * 16) ? 'filled' : ''}"></i>`).join('');
  return `<section class="hv-battery-layout" aria-label="Butiran bateri voltan tinggi">
    <div class="hv-pack-panel"><div class="panel-overline"><span>HV BATTERY · e.MAS 5</span><span class="live-indicator ${d.live && !state.isSimulation ? 'on' : ''}">${state.isSimulation ? 'SIMULASI' : d.live ? 'LIVE' : 'NO SIGNAL'}</span></div>
      <div class="pack-visual"><div class="pack-topline"><span>STATE OF CHARGE</span><span>0—100%</span></div><div class="pack-readout"><strong>${d.value(d.soc)}</strong><span>%</span><small>${d.live ? 'BACAAN PEK' : 'MENUNGGU DATA'}</small></div><div class="pack-shell"><div class="pack-fill" style="width:${level}%"></div><div class="pack-segments">${segments}</div><b></b></div><div class="pack-scale"><span>EMPTY</span><span>${d.live ? d.time : '—'}</span><span>FULL</span></div></div>
      <div class="pack-note">Segmen ialah visualisasi paras cas keseluruhan, bukan bacaan sel individu.</div></div>
    <div class="hv-metric-stack"><article class="hv-metric voltage-metric"><span>VOLTAN PEK</span><strong>${d.value(d.voltage, 1)}<small> V</small></strong><div class="metric-track"><i style="width:${d.voltage === null ? 0 : 72}%"></i></div><em>Bacaan sistem</em></article><article class="hv-metric current-metric"><span>ARUS PEK</span><strong style="color:${d.current !== null ? (d.current > 0.1 ? '#ef4444' : d.current < -0.1 ? '#10b981' : 'inherit') : 'inherit'}">${d.value(d.current, 1)}<small> A</small></strong><em>${d.current === null ? 'Arah arus belum diketahui' : d.current > 0.1 ? 'Keluar dari pek · Beban / Pemanduan' : d.current < -0.1 ? 'Masuk ke pek · Cas / Rekuperasi' : 'Hampir sifar'}</em></article><div class="hv-caution">Data yang tersedia sahaja dipaparkan. Suhu, kesihatan bateri dan kapasiti tidak tersedia dalam bacaan ini.</div></div>
  </section>`;
}
function powerFlowPage() {
  const d = energyReadings();
  const isOut = d.current !== null && d.current > 0.1;
  const isIn = d.current !== null && d.current < -0.1;
  const isIdle = d.current !== null && !isOut && !isIn;
  const direction = d.current === null ? 'unknown' : isOut ? 'out' : isIn ? 'in' : 'idle';

  const headingText = isOut ? 'KUASA KELUAR DARI PEK' : isIn ? 'KUASA MASUK KE PEK' : isIdle ? 'ARUS HAMPIR SIFAR' : 'ARAH ALIRAN BELUM DIKETAHUI';
  const headingColor = isOut ? '#ef4444' : isIn ? '#10b981' : 'inherit';
  const label = isOut ? 'ARUS KELUAR · BEBAN & TRAKSI' : isIn ? 'ARUS MASUK · CAS & REGEN' : isIdle ? 'TIADA ALIRAN BERSIH' : 'MENUNGGU ARUS';
  const arrow = isOut ? '▶' : isIn ? '◀' : '↔';
  const absKw = d.power !== null ? Math.abs(d.power) : null;
  const currentStr = d.current !== null ? Math.abs(d.current).toFixed(1) : '—';
  const voltStr = d.voltage !== null ? d.voltage.toFixed(1) : '—';
  const footNote = isOut 
    ? '🔴 Arus mengalir keluar dari pek bateri voltan tinggi untuk memacu motor traksi dan beban kenderaan.'
    : isIn 
      ? '🟢 Arus mengalir masuk ke pek bateri voltan tinggi (pengecasan AC/DC atau brek regeneratif).'
      : '⚪ Arus bateri hampir sifar (kenderaan bersedia / tiada beban ketara).';

  return `<section class="power-flow-board flow-mode-${direction}" aria-label="Aliran kuasa bateri">
    <div class="flow-heading">
      <div>
        <span class="eyebrow">POWER FLOW · BACAAN PEK</span>
        <h2 id="pf-heading" style="color:${headingColor}">${headingText}</h2>
      </div>
      <span class="flow-live ${d.live && !state.isSimulation ? 'on' : ''}">${state.isSimulation ? '◉ SIMULASI' : d.live ? '● DATA IHU' : '○ TIADA DATA'}</span>
    </div>
    <div class="flow-canvas direction-${direction}" id="pf-canvas">
      <div class="flow-node">
        <span class="node-symbol">${icon('battery')}</span>
        <strong>PEK HV</strong>
        <small id="pf-soc">${d.value(d.soc, 0)}% CAS</small>
      </div>
      <div class="flow-rail">
        <div class="flow-line"><i></i><i></i><i></i></div>
        <span class="flow-arrow" id="pf-arrow">${arrow}</span>
        <small id="pf-label">${label}</small>
      </div>
      <div class="flow-result">
        <span>KUASA PEK</span>
        <strong id="pf-power">${d.value(absKw, 2)}<small> kW</small></strong>
        <em id="pf-formula">${voltStr} V × ${currentStr} A</em>
      </div>
    </div>
    <div class="flow-foot">
      <span id="pf-foot-note">${footNote}</span>
      <span id="pf-time">Kemas kini: ${d.time}</span>
    </div>
  </section>`;
}
const pages = [
  ['overview', 'Overview'],
  ['battery', 'HV Battery'],
  ['power', 'Power Flow'],
  ['trip', 'Trip'],
  ['climate', 'Climate'],
  ['controls', 'Controls'],
  ['megafon', 'Megafon']
];

const state = {
  seatbelt: { driver: -1, passenger: -1 },
  camp: false,
  vent: false,
  power: true,
  avas: null,
  avasSlot: 3,
  avasConfig: {
    1: { name: 'Slot 1 (Galaxy)', custom_name: 'Paddle Pop', volume: [0, 2, 5, 6, 7, 2, 0], pitch: [100, 100, 100, 100, 100, 100, 100] },
    2: { name: 'Slot 2 (Space)', custom_name: 'Space Pulse', volume: [0, 6, 8, 11, 14, 6, 0], pitch: [100, 100, 100, 100, 100, 100, 100] },
    3: { name: 'Slot 3 (Pixel)', custom_name: 'Super Mario Bros Theme', volume: [0, 8, 11, 14, 16, 8, 0], pitch: [100, 102, 105, 108, 112, 116, 120] }
  },
  setpoint: 22.0,
  setpointR: 22.0,
  trip: {
    distanceKm: null,
    durationMin: null,
    avgSpeedKmh: null,
    consumptionKwh100: null,
    energyKwh: null,
    distance2Km: null
  },
  fan: 1,
  hvacDataAvailable: false,
  fanOff: false,
  airMode: 0,
  scenario: 'drive',
  isLive: false,
  soc: 58.5,
  voltage: 395.4,
  current: 0.3,
  motorPower: null,
  vehicleTelemetryAvailable: false,
  hvacPower: 990,
  auxPower: 160,
  battTemp: 31.0,
  motorTemp: 48.0,
  inverterTemp: 42.0,
  ambientTemp: 32.0,
  range: 195,
  speed: 0.0,
  soh: 98.2,
  gear: 'D',
  doors: { fl: false, fr: false, rl: false, rr: false, trunk: false, frunk: false },
  doorKnown: { fl:false, fr:false, rl:false, rr:false, trunk:false, frunk:false },
  doorDataAvailable: false,
  tpmsAvailable: false,
  tpms: {
    fl: { p: 2.5, t: 31 },
    fr: { p: 2.5, t: 32 },
    rl: { p: 2.4, t: 30 },
    rr: { p: 2.4, t: 31 }
  }
};

const scenarios = {
  idle:  { v: 397.9, a: 1.4,   motor: 0.0,   hvac: 160,  aux: 350, gear: 'P' },
  drive: { v: 395.2, a: 48.2,  motor: 17.4,  hvac: 1070, aux: 420, gear: 'D' },
  regen: { v: 401.2, a: -80.0, motor: -31.8, hvac: 1070, aux: 420, gear: 'D' }
};

const metric = (label, value, unit = '') => `
  <div class="metric">
    <small>${label}</small>
    <strong>${value} <span>${unit}</span></strong>
  </div>`;

const head = (title, target, tag = '') => `
  <div class="card-head">
    <h2>${title}</h2>
    ${target ? `<a href="#${target}" aria-label="Open ${title}">↗</a>` : `<span class="badge">${tag || (state.isLive ? 'LIVE' : 'DEMO')}</span>`}
  </div>`;

const car = `
  <div class="vehicle-stage">
    <img src="assets/emas5-jade.png" class="car-art" alt="Proton e.MAS 5 - Jade Green" />
    <div class="car-shadow"></div>
  </div>`;

function getPowerValues() {
  if (state.isLive) {
    const net = (state.voltage * state.current) / 1000;
    return {
      v: state.voltage,
      a: state.current,
      motor: state.motorPower,
      hvac: state.hvacPower,
      aux: state.auxPower,
      net: net
    };
  }
  const d = scenarios[state.scenario];
  const net = (d.v * d.a) / 1000;
  return {
    v: d.v,
    a: d.a,
    motor: d.motor,
    hvac: d.hvac,
    aux: d.aux,
    net: net
  };
}

function batteryHero() {
  const socVal = state.soc.toFixed(1);
  const socNum = Math.round(state.soc);
  const activeSegments = Math.round((socNum / 100) * 24);
  return `
    <section class="card hero-battery">
      ${head('HV Battery', 'battery')}
      <div class="big" id="live-hero-soc">${socVal}<small> %</small></div>
      <span class="hint">STATE OF CHARGE · 40.16 kWh</span>
      <div class="battery-track" id="live-battery-track">
        ${Array.from({ length: 24 }, (_, i) => `<i class="${i >= activeSegments ? 'empty' : ''}"></i>`).join('')}
      </div>
      <div class="subrow">
        <span>Estimated Range</span>
        <strong id="live-hero-range">${state.range} <small>km</small></strong>
      </div>
      <div class="mini-grid">
        <div class="metric"><small>Pack Temperature</small><strong id="live-hero-temp">${state.battTemp.toFixed(1)} <span>°C</span></strong></div>
        <div class="metric"><small>State of Health</small><strong id="live-hero-soh">${state.soh.toFixed(1)} <span>%</span></strong></div>
      </div>
    </section>`;
}

function flowCard() {
  const p = getPowerValues();
  const isRegen = p.net < 0;
  return `
    <section class="card power-card">
      ${head('Power Flow', 'power')}
      <div class="flow-total" id="live-flow-total" style="color: ${isRegen ? '#4e8c56' : 'inherit'}">
        ${p.net > 0 ? '+' : ''}${p.net.toFixed(2)} <span>kW</span>
      </div>
      <span class="hint" id="live-flow-hint">${isRegen ? 'REGENERATIVE BRAKING' : (p.net > 0.3 ? 'TRACTION & AUX POWER' : 'STANDBY & AUX LOAD')}</span>
      <div class="flow-row">
        <div class="flow-label"><span>Traction Motor</span><strong id="live-motor-val">${p.motor >= 0 ? '+' : ''}${p.motor.toFixed(1)} kW</strong></div>
        <div class="bar"><i id="live-motor-bar" style="width: ${Math.min(100, (Math.abs(p.motor) / 85) * 100)}%"></i></div>
      </div>
      <div class="flow-row">
        <div class="flow-label"><span>HVAC Climate</span><strong id="live-hvac-val">${Math.round(p.hvac)} W</strong></div>
        <div class="bar"><i id="live-hvac-bar" style="width: ${Math.min(100, (p.hvac / 2500) * 100)}%"></i></div>
      </div>
      <div class="flow-row">
        <div class="flow-label"><span>12V DC-DC Converter</span><strong id="live-aux-val">${Math.round(p.aux)} W</strong></div>
        <div class="bar"><i id="live-aux-bar" style="width: ${Math.min(100, (p.aux / 1000) * 100)}%"></i></div>
      </div>
    </section>`;
}

function chartCard() {
  if (typeof powerGraphMarkup === 'function') {
    return powerGraphMarkup();
  }
  return `
    <section class="card chart-card">
      ${head('Recuperation History', 'trip')}
      <div class="chart-summary">
        <strong>22.4<span> %</span></strong>
        <span>Recuperated Energy Ratio</span>
      </div>
      <svg class="chart" viewBox="0 0 640 100" preserveAspectRatio="none" role="img" aria-label="Energy recuperation graph">
        <path d="M0 20H640 M0 45H640 M0 70H640" stroke="var(--line)" stroke-dasharray="3 5"/>
        <path d="M0 70 20 66 40 70 60 54 80 57 100 42 120 50 140 24 160 36 180 29 200 50 220 43 240 56 260 31 280 39 300 16 320 30 340 24 360 49 380 42 400 58 420 41 440 48 460 28 480 39 500 22 520 38 540 34 560 53 580 45 600 60 620 51 640 64V80H0Z" fill="var(--soft)"/>
        <path d="M0 70 20 66 40 70 60 54 80 57 100 42 120 50 140 24 160 36 180 29 200 50 220 43 240 56 260 31 280 39 300 16 320 30 340 24 360 49 380 42 400 58 420 41 440 48 460 28 480 39 500 22 520 38 540 34 560 53 580 45 600 60 620 51 640 64" fill="none" stroke="var(--accent)" stroke-width="2"/>
        <path d="M0 78 40 77 60 73 80 77 120 75 140 66 160 72 200 76 240 75 260 67 280 75 300 62 320 73 360 76 400 75 440 77 460 67 480 75 500 66 520 74 560 78 600 75 640 78" fill="none" stroke="#a8bb87" stroke-width="2"/>
        <text x="0" y="96">0 min</text>
        <text x="207" y="96">10 min</text>
        <text x="417" y="96">20 min</text>
        <text x="603" y="96">30 min</text>
      </svg>
      <div class="chart-footer">
        <span>Trip · 42.6 km</span>
        <div class="legend">
          <span><i></i>Traction</span>
          <span><i></i>Regen</span>
        </div>
      </div>
    </section>`;
}

function quickButton(name, label, subtitle, on) {
  return `
    <button class="quick" data-action="${name}" aria-pressed="${on}">
      ${icon(name === 'camp' ? 'camp' : 'vent')}
      <span>${label}<small>${subtitle}</small></span>
      <span class="toggle ${on ? 'on' : ''}" aria-hidden="true"></span>
    </button>`;
}

function overview() {
  const gearText = state.gear === 'P' ? 'P · PARK' : (state.gear === 'R' ? 'R · REVERSE' : (state.gear === 'N' ? 'N · NEUTRAL' : 'D · DRIVE'));
  return `
    <div class="dashboard">
      ${batteryHero()}
      <section class="card vehicle-card">
        <div class="card-head">
          <h2>Proton e.MAS 5</h2>
          <span class="badge" id="live-gear-badge">${state.power ? gearText : 'HV OFF'}</span>
        </div>
        <p class="hint vehicle-edition">JADE GREEN · ELECTRIC</p>
        ${car}
        <div class="vehicle-bottom">
          <div>
            <strong>${state.vent ? '5% Ajar' : 'Closed'}</strong>
            <small>Windows</small>
          </div>
          <div>
            <strong id="live-12v-val">13.8 <small>V</small></strong>
            <small>12V Battery</small>
          </div>
          <div>
            <strong id="live-target-temp">${formatSetpoint(state.setpoint)}</strong>
            <small>Target</small>
          </div>
        </div>
      </section>
      ${flowCard()}
      ${chartCard()}
      <section class="card quick-card">
        ${head('Cabin Controls', 'controls')}
        <div class="quick-list">
          ${quickButton('camp', 'Camp Mode', state.camp ? 'Active' : 'Standby', state.camp)}
          ${quickButton('vent', 'Heat Vent', state.vent ? 'Windows 5%' : 'Closed', state.vent)}
          <a class="quick" href="#controls" style="text-decoration:none;">
            ${icon('sound')}
            <span>AVAS Sound<small id="live-overview-avas-name">${state.avas === 0 ? 'Off' : (state.avasConfig[state.avas] ? (state.avasConfig[state.avas].custom_name || state.avasConfig[state.avas].name) : `Slot ${state.avas}`)}</small></span>
            <span class="badge" id="live-overview-avas-badge" style="margin-left:auto;">${state.avas === 0 ? 'OFF' : `SLOT ${state.avas}`}</span>
          </a>
        </div>
        <a class="comfort-link" href="#climate"><span>Target <strong>${formatSetpoint(state.setpoint)}</strong></span><span>Climate ↗</span></a>
      </section>
    </div>`;
}

function detailsGrid(items) {
  return `
    <div class="detail-grid">
      ${items.map(([label, val, unit], index) => `
        <section class="card stat-card">
          <span class="stat-index" aria-hidden="true">${String(index + 1).padStart(2, '0')}</span>
          ${metric(label, val, unit)}
        </section>
      `).join('')}
    </div>`;
}

function batteryPage() {
  const p = getPowerValues();
  return detailsGrid([
    ['State of Charge (SOC)', state.soc.toFixed(1), '%'],
    ['HV Bus Voltage', p.v.toFixed(1), 'V'],
    ['Battery Current', p.a.toFixed(1), 'A'],
    ['Pack Temperature', state.battTemp.toFixed(1), '°C'],
    ['State of Health (SOH)', state.soh.toFixed(1), '%'],
    ['Net Battery Power', p.net.toFixed(2), 'kW'],
    ['Pack Chemistry', 'LFP Blade', ''],
    ['Usable Capacity', '40.16', 'kWh'],
    ['Cell Voltage Balance', '3.28', 'V']
  ]);
}

function powerPage() {
  const p = getPowerValues();
  return `
    <div class="scenario-toolbar">
      <span class="eyebrow">TELEMETRI KUASA & TRAKSI LANGSUNG</span>
      <span class="pill"><i class="connection-dot"></i>${state.isLive ? 'Bacaan terakhir' : 'Data contoh'}</span>
    </div>
    <div class="dashboard">
      ${flowCard()}
      <section class="card">
        ${head('Powertrain Thermal Status')}
        <div class="mini-grid">
          <div class="metric"><small>Traction Motor</small><strong id="live-motor-temp">${state.motorTemp.toFixed(0)} <span>°C</span></strong></div>
          <div class="metric"><small>Power Inverter</small><strong id="live-inverter-temp">${state.inverterTemp.toFixed(0)} <span>°C</span></strong></div>
        </div>
      </section>
      <section class="card">
        ${head('High Voltage Bus Telemetry')}
        <div class="mini-grid">
          <div class="metric"><small>HV Bus Voltage</small><strong id="live-bus-volt">${p.v.toFixed(1)} <span>V</span></strong></div>
          <div class="metric"><small>Instant Amperage</small><strong id="live-bus-curr">${p.a.toFixed(1)} <span>A</span></strong></div>
        </div>
      </section>
    </div>`;
}

function formatTripDuration(min) {
  if (typeof min !== 'number' || min < 0) return '—';
  const h = Math.floor(min / 60);
  const m = min % 60;
  if (h <= 0) return m + ' min';
  return h + 'j ' + m + 'm';
}

function tripNum(n, digits) {
  return typeof n === 'number' ? n.toFixed(digits) : '—';
}

function tripPage() {
  const t = state.trip;
  const cards = [
    ['trip-distance', 'Jarak Trip 1', tripNum(t.distanceKm, 1), 'km'],
    ['trip-duration', 'Masa Trip 1', formatTripDuration(t.durationMin), ''],
    ['trip-speed', 'Purata laju', typeof t.avgSpeedKmh === 'number' ? String(t.avgSpeedKmh) : '—', 'km/j'],
    ['trip-use', 'Penggunaan', tripNum(t.consumptionKwh100, 1), 'kWh/100 km'],
    ['trip-energy', 'Tenaga bersih', tripNum(t.energyKwh, 2), 'kWh'],
    ['trip-distance2', 'Jarak Trip 2', tripNum(t.distance2Km, 1), 'km']
  ];

  return `
    <div class="detail-grid">
      ${cards.map(([id, label, val, unit], index) => `
        <section class="card stat-card">
          <span class="stat-index" aria-hidden="true">${String(index + 1).padStart(2, '0')}</span>
          <div class="metric">
            <small>${label}</small>
            <strong id="${id}">${val}${unit ? ` <span>${unit}</span>` : ''}</strong>
          </div>
        </section>
      `).join('')}
    </div>

    <!-- Analisis Kecekapan Tenaga & Penjimatan Perjalanan (Zero duplicate graph!) -->
    <div class="trip-analysis-grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap:16px; margin-top:16px;">
      <section class="card"><h2>Data perjalanan</h2><p>Kos, peratus rekuperasi dan pecahan beban belum disediakan oleh daemon. Anggaran akan memerlukan kadar pengguna dan sampel tenaga yang sah.</p></section>
      <section class="card stat-card" style="padding:18px;">
        <div class="card-head" style="margin-bottom:12px;">
          <h2 style="font-size:15px;">Status Pengesanan Trip (Odo)</h2>
          <span class="badge">CAN BUS</span>
        </div>
        <div style="display:flex; flex-direction:column; gap:8px;">
          <div style="display:flex; justify-content:space-between; font-size:13px;">
            <span style="color:var(--muted);">Trip 1 (Semasa):</span>
            <strong>${tripNum(t.distanceKm, 1)} km · ${formatTripDuration(t.durationMin)}</strong>
          </div>
          <div style="display:flex; justify-content:space-between; font-size:13px;">
            <span style="color:var(--muted);">Trip 2 (Jarak Jauh):</span>
            <strong>${tripNum(t.distance2Km, 1)} km</strong>
          </div>
          <div style="display:flex; justify-content:space-between; font-size:13px;">
            <span style="color:var(--muted);">Baki Jarak DTE:</span>
            <strong style="color:#3db66e;">${state.isLive && Number.isFinite(state.range) ? state.range.toFixed(0) : '—'} km</strong>
          </div>
        </div>
      </section>
    </div>`;
}

function formatSetpoint(v) {
  const n = Math.round(v);
  if (n <= 17) return '17° (LO)';
  if (n >= 24) return '24° (MAX)';
  return `${n}°`;
}

function climatePage() {
  if (!state.hvacDataAvailable) return '<section class="card"><h2>Keadaan penghawa dingin</h2><p>Tiada bacaan penghawa dingin daripada daemon. Kawalan belum tersedia melalui kontrak semasa.</p></section>';
  const airModes = ['Fresh Air', 'Recirculation', 'Defogger'];
  return `
    <div class="detail-grid">
      <section class="card climate-card-main">
        ${head('Suhu')}
        <div class="climate-value">
          <strong id="setpoint-value">${formatSetpoint(state.setpoint)}</strong><span>°C</span>
        </div>
        <div class="climate-stepper-row">
          <button class="stepper-btn cooler-btn" data-action="cooler" aria-label="Decrease temperature">
            <span class="stepper-sym">−</span>
          </button>
          <div class="stepper-track-wrap">
            <input class="climate-slider temperature-slider" id="setpoint-slider" type="range" min="17" max="24" step="1" value="${Math.min(24, Math.max(17, Math.round(state.setpoint)))}" aria-label="Temperature">
          </div>
          <button class="stepper-btn warmer-btn" data-action="warmer" aria-label="Increase temperature">
            <span class="stepper-sym">+</span>
          </button>
        </div>
      </section>
      <section class="card climate-card-main">
        ${head('Blower Fan Speed')}
        <div class="climate-value">
          <strong id="fan-value">${state.fanOff ? 'OFF' : state.fan}</strong><span>${state.fanOff ? '' : '/ 8'}</span>
        </div>
        <div class="climate-stepper-row">
          <button class="stepper-btn fan-down-btn" data-action="fan-down" aria-label="Decrease Fan" ${state.fanOff ? 'disabled' : ''}>
            <span class="stepper-sym">−</span>
          </button>
          <div class="stepper-track-wrap">
            <input class="climate-slider fan-slider" id="fan-slider" type="range" min="1" max="8" step="1" value="${state.fan}" aria-label="Fan Speed" ${state.fanOff ? 'disabled' : ''}>
          </div>
          <button class="stepper-btn fan-up-btn" data-action="fan-up" aria-label="Increase Fan" ${state.fanOff ? 'disabled' : ''}>
            <span class="stepper-sym">+</span>
          </button>
        </div>
        <div class="climate-actions">
          <button class="climate-off ${state.fanOff ? 'active' : ''}" data-action="fan-off">${state.fanOff ? 'A/C OFF' : 'A/C ON'}</button>
          <button class="air-mode ${state.airMode === 0 ? 'active' : ''}" data-action="set-air" data-air="0">${icon('air_fresh')}<span>Fresh Air</span></button>
          <button class="air-mode ${state.airMode === 1 ? 'active' : ''}" data-action="set-air" data-air="1">${icon('air_recirc')}<span>Recirc</span></button>
          <button class="air-mode ${state.airMode === 2 ? 'active' : ''}" data-action="set-air" data-air="2">${icon('defog')}<span>Defogger</span></button>
        </div>
      </section>
      <section class="card">
        ${metric('Ambient Temperature', state.isLive && Number.isFinite(state.ambientTemp) ? state.ambientTemp.toFixed(1) : '—', '°C')}
      </section>
      <section class="card">
        ${metric('A/C Compressor Load', state.hvacPower > 40 ? Math.round(state.hvacPower - 40) : 0, 'W')}
      </section>
      <section class="card">
        ${metric('Blower Fan Load', '40', 'W')}
      </section>
      <section class="card">
        ${metric('Total Climate Power', Math.round(state.hvacPower), 'W')}
      </section>
    </div>`;
}


function fetchAvasConfig() {
  vehicleFetch('/api/avas/config')
    .then(r => r.json())
    .then(data => {
      if (data && data.slots) {
        state.avasConfig = data.slots;
        if (data.active_slot !== undefined) {
          state.avas = data.active_slot;
          if (data.active_slot > 0) state.avasSlot = data.active_slot;
        }
        if (location.hash === '#controls') render();
      }
    })
    .catch(() => {});
}
fetchAvasConfig();

function renderAvasSvgCurve(vols) {
  const xs = [25, 75, 135, 195, 255, 315, 375];
  const pts = xs.map((x, i) => {
    const v = vols && vols[i] !== undefined ? vols[i] : 0;
    const y = 72 - (v / 16) * 55;
    return { x, y, v };
  });

  const polyPts = pts.map(p => `${p.x},${p.y.toFixed(1)}`).join(' ');
  const areaPts = `25,75 ${polyPts} 375,75`;

  const circles = pts.map(p => `
    <circle cx="${p.x}" cy="${p.y.toFixed(1)}" r="4.5" fill="var(--accent)" stroke="var(--card)" stroke-width="1.5" />
    <text x="${p.x}" y="${Math.max(12, p.y - 7).toFixed(1)}" fill="var(--ink)" font-size="10" font-weight="700" text-anchor="middle">${p.v}</text>
  `).join('');

  return `
    <svg viewBox="0 0 400 85" width="100%" height="85" style="display:block;">
      <defs>
        <linearGradient id="curve-grad" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="var(--accent)" stop-opacity="0.35"/>
          <stop offset="100%" stop-color="var(--accent)" stop-opacity="0.02"/>
        </linearGradient>
      </defs>
      <line x1="20" y1="72" x2="380" y2="72" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 3"/>
      <line x1="20" y1="44" x2="380" y2="44" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 3"/>
      <line x1="20" y1="17" x2="380" y2="17" stroke="var(--line)" stroke-width="1" stroke-dasharray="3 3"/>
      <polygon points="${areaPts}" fill="url(#curve-grad)" />
      <polyline points="${polyPts}" fill="none" stroke="var(--accent)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
      ${circles}
    </svg>
  `;
}

window.refreshUsbAudioList = function() {
  const container = document.getElementById('usb-file-list');
  if (!container) return;
  container.innerHTML = '<p class="usb-loading">Mengimbas pemacu USB untuk fail audio (.wav / .mp3)...</p>';
  vehicleFetch('/api/avas/usb_files')
    .then(r => r.json())
    .then(data => {
      if (!data.files || data.files.length === 0) {
        container.innerHTML = `
          <div style="text-align:center;padding:24px 10px;">
            <p style="font-size:1.1rem;font-weight:600;margin-bottom:6px;">Tiada fail audio ditemui di pemacu USB</p>
            <p style="font-size:12px;color:var(--muted);margin-bottom:12px;">Sila masukkan pendrive USB yang mengandungi lagu (.wav atau .mp3) ke port USB kereta.</p>
            <button type="button" class="secondary" onclick="refreshUsbAudioList()">🔄 Imbas Semula</button>
          </div>
        `;
        return;
      }
      container.innerHTML = data.files.map(f => {
        const sizeMb = (f.size / (1024 * 1024)).toFixed(1);
        const isMp3 = f.name.toLowerCase().endsWith('.mp3');
        return `
          <div class="usb-file-item">
            <div class="usb-file-info">
              <span class="usb-file-name">🎵 ${f.name}</span>
              <span class="usb-file-size">${isMp3 ? 'MP3 Audio' : 'WAV Audio'} · ${sizeMb} MB</span>
            </div>
            <div class="usb-file-actions">
              <button type="button" class="secondary" style="font-size:11px;padding:6px 10px;" onclick="previewAudioTrack('${encodeURIComponent(f.path)}')">▶ Dengar</button>
              <button type="button" class="primary" style="font-size:11px;padding:6px 12px;" onclick="installUsbToSlot('${encodeURIComponent(f.path)}', '${encodeURIComponent(f.name)}')">Pasang ke Slot ${state.avasSlot || 3}</button>
            </div>
          </div>
        `;
      }).join('');
    })
    .catch(err => {
      container.innerHTML = `<p style="color:#d32f2f;padding:12px;">Gagal mengimbas USB: ${err.message}</p>`;
    });
};

window.previewAudioTrack = function(encodedPath) {
  const path = decodeURIComponent(encodedPath);
  try {
    if (window.AndroidEnergy && typeof window.AndroidEnergy.playAudioPath === 'function') {
      window.AndroidEnergy.playAudioPath(path);
      toast('Memainkan pratonton audio...');
      return;
    }
  } catch (e) {}
  const audioUrl = getApiBase() + `/api/avas/preview?path=${encodeURIComponent(path)}`;
  const a = new Audio(audioUrl);
  a.play().then(() => toast('Memainkan pratonton audio...')).catch(err => toast('Pratonton gagal: ' + err.message));
};

window.installUsbToSlot = function(encodedPath, encodedName) {
  const path = decodeURIComponent(encodedPath);
  const name = decodeURIComponent(encodedName).replace(/\.[^/.]+$/, "");
  const slot = state.avasSlot || 3;
  toast(`Memasang "${name}" ke Slot ${slot}...`);
  vehicleFetch('/api/avas/apply_usb', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slot: slot, file_path: path, custom_name: name })
  })
  .then(r => r.json())
  .then(res => {
    if (res.ok) {
      if (state.avasConfig[slot]) state.avasConfig[slot].custom_name = name;
      window.closeUsbModal();
      toast(`Berjaya! "${name}" dipasang ke Slot ${slot}! 🎶`);
      render();
    } else {
      toast(`Gagal: ${res.error || 'Ralat pemasangan'}`);
    }
  })
  .catch(err => toast(`Ralat: ${err.message}`));
};

window.closeUsbModal = function() {
  const modal = document.getElementById('usb-modal');
  if (modal) modal.style.display = 'none';
};

window.openUsbModal = function() {
  const modal = document.getElementById('usb-modal');
  if (modal) {
    const title = document.getElementById('usb-modal-title');
    if (title) title.textContent = `Pilih Muzik untuk Slot ${state.avasSlot || 3}`;
    modal.style.display = 'flex';
    toast('Membuka pengimbas muzik USB...');
    window.refreshUsbAudioList();
  } else {
    toast('Ralat: usb-modal tidak ditemui');
  }
};

let tuningDebounceTimer = null;
function scheduleAutoApplyTuning() {
  clearTimeout(tuningDebounceTimer);
  tuningDebounceTimer = setTimeout(() => {
    window.saveAvasTuningDirect(true);
  }, 500);
}

window.applyPresetDirect = function(pr) {
  const presets = {
    gentle: [0, 2, 3, 4, 4, 2, 0],
    balanced: [0, 3, 5, 6, 7, 3, 0],
    sport: [0, 8, 11, 14, 16, 8, 0]
  };
  if (presets[pr]) {
    const slot = state.avasSlot || 3;
    if (state.avasConfig[slot]) state.avasConfig[slot].volume = [...presets[pr]];
    render();
    window.saveAvasTuningDirect(false);
  }
};

window.saveAvasTuningDirect = function(silent = false) {
  const slot = state.avasSlot || 3;
  const sInfo = state.avasConfig[slot] || { volume: [0, 8, 11, 14, 16, 8, 0], pitch: [100, 102, 105, 108, 112, 116, 120] };
  if (!silent) toast(`Menerapkan tuning Slot ${slot} ke kereta...`);
  vehicleFetch('/api/avas/save', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      slot: slot,
      volume: sInfo.volume,
      pitch: sInfo.pitch || [100, 102, 105, 108, 112, 116, 120],
      mute_chime: true,
      custom_name: sInfo.custom_name || ''
    })
  })
  .then(r => r.json())
  .then(res => {
    if (res.ok) {
      toast(`Tuning Slot ${slot} berjaya diterapkan! 🚗💨`);
    } else {
      if (!silent) toast(`Gagal menerapkan tuning: ${res.error || 'Ralat'}`);
    }
  })
  .catch(err => {
    if (!silent) toast(`Ralat rangkaian: ${err.message}`);
  });
};

function controlsPage() {
  return `
    <div class="controls-grid">
      <section class="card control-card lock-sound-card" id="lock-sound-panel"></section>
      <section class="card control-card">
        ${icon('vent')}
        <h2>Pengudaraan cermin</h2>
        <p>Buka sedikit untuk pengudaraan atau tutup semua cermin. Kedudukan sebenar perlu diperiksa pada kereta.</p>
        <div class="lock-sound-actions"><button type="button" class="secondary" data-action="window-crack" ${canControlVehicle() ? '' : 'disabled'}>Buka sedikit</button>
        <button type="button" class="primary" data-action="window-close" ${canControlVehicle() ? '' : 'disabled'}>Tutup semua</button></div>
        <p role="status" id="window-command-status">Belum ada arahan dihantar.</p>
      </section>
      <section class="card control-card avas-card">
        <div class="avas-header">
          <div class="avas-title-row">
            ${icon('sound')}
            <h2>AVAS Sound & Tuning Curve</h2>
          </div>
          <span class="badge">${state.avas === null ? 'BELUM DISAHKAN' : state.avas === 0 ? 'MUTED' : `SLOT ${state.avas} AKTIF`}</span>
        </div>

        <!-- 4 Mode Buttons: OFF, SLOT 1, SLOT 2, SLOT 3 -->
        <div class="avas-slot-buttons">
          <button class="avas-btn ${state.avas === 0 ? 'active' : ''}" data-action="set-avas" data-avas="0">
            <strong>OFF</strong>
            <small>Senyap / Mute</small>
          </button>
          <button class="avas-btn ${state.avas === 1 ? 'active' : ''}" data-action="set-avas" data-avas="1">
            <strong>SLOT 1</strong>
            <small>${state.avasConfig[1] ? (state.avasConfig[1].custom_name || 'Galaxy') : 'Galaxy'}</small>
          </button>
          <button class="avas-btn ${state.avas === 2 ? 'active' : ''}" data-action="set-avas" data-avas="2">
            <strong>SLOT 2</strong>
            <small>${state.avasConfig[2] ? (state.avasConfig[2].custom_name || 'Space') : 'Space'}</small>
          </button>
          <button class="avas-btn ${state.avas === 3 ? 'active' : ''}" data-action="set-avas" data-avas="3">
            <strong>SLOT 3</strong>
            <small>${state.avasConfig[3] ? (state.avasConfig[3].custom_name || 'Pixel') : 'Pixel'}</small>
          </button>
        </div>

        <!-- Tuning Curve for Selected Slot -->
        ${(() => {
          const curSlot = state.avasSlot || 3;
          const slotInfo = state.avasConfig[curSlot] || {
            name: `Slot ${curSlot}`,
            custom_name: '',
            volume: [0, 3, 5, 6, 7, 3, 0]
          };
          const vols = slotInfo.volume || [0, 3, 5, 6, 7, 3, 0];
          const speeds = [0, 5, 10, 15, 20, 25, 30];

          return `
          <div class="avas-tuning-panel">
            <div class="avas-curve-meta">
              <div class="avas-track-info">
                <span>🎵 ${slotInfo.custom_name ? slotInfo.custom_name : slotInfo.name}</span>
                <small style="color:var(--muted);font-weight:normal;">(Menala Slot ${curSlot})</small>
              </div>
              <span class="badge" style="font-size:10px;">${state.avas === 0 ? 'Speaker Senyap' : (state.avas === curSlot ? 'Aktif di Speaker' : 'Standby')}</span>
            </div>

            <!-- SVG Visual Curve -->
            <div class="avas-svg-container" id="avas-svg-wrap">
              ${renderAvasSvgCurve(vols)}
            </div>

            <!-- 7 Speed Sliders -->
            <div class="avas-sliders-grid">
              ${speeds.map((spd, i) => `
                <div class="speed-slider-col">
                  <span class="speed-val" id="speed-val-${i}">${vols[i] !== undefined ? vols[i] : 0}</span>
                  <input type="range" min="0" max="15" value="${vols[i] !== undefined ? vols[i] : 0}" data-action="tuning-slider" data-index="${i}">
                  <span class="speed-label">${spd} <small>km/j</small></span>
                </div>
              `).join('')}
            </div>

            <!-- Actions Bar: Presets + USB + Save -->
            <div class="avas-actions-bar">
              <div class="avas-presets-group">
                <span style="font-size:11px;color:var(--muted);font-weight:600;">Preset:</span>
                <button type="button" class="preset-btn" data-action="apply-preset" data-preset="gentle">Lembut</button>
                <button type="button" class="preset-btn" data-action="apply-preset" data-preset="balanced">Seimbang</button>
                <button type="button" class="preset-btn" data-action="apply-preset" data-preset="sport">Sporty</button>
              </div>
              <div class="avas-main-btns">
                <button type="button" class="secondary" data-action="open-usb-modal" style="font-size:12px;padding:8px 12px;">📁 Muzik USB</button>
                <button type="button" class="primary" data-action="save-tuning" onclick="saveAvasTuningDirect()" style="font-size:12px;padding:8px 14px;">💾 Terapkan Tuning</button>
              </div>
            </div>
          </div>
          `;
        })()}
      </section>
      <section class="card control-card jarvis-card" style="grid-column: 1 / -1; border: 1px solid rgba(0,229,255,0.3); background: linear-gradient(135deg, rgba(21,25,34,0.9), rgba(15,23,42,0.95)); padding: 18px 20px; border-radius: 16px;">
        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
          <div style="display:flex; align-items:center; gap:12px;">
            <span style="font-size:24px;">🎙️</span>
            <div>
              <h2 style="margin:0; font-size:16px; color:#00E5FF; font-weight:700;">AI Cabin Voice Assistant (K.I.T.T. / Grok · e.MAS 5)</h2>
              <small style="color:var(--muted); font-size:12px;">Floating Bubble "Always-On-Top" · Suara Stereo Osman · Persona Grok / K.I.T.T.</small>
            </div>
          </div>
          <span class="badge" id="jarvis-status-badge" style="background:rgba(0,229,255,0.15); color:#00E5FF; border:1px solid rgba(0,229,255,0.4); font-size:11px; padding:4px 10px; border-radius:12px;">TERAPUNG AKTIF</span>
        </div>
        <p style="font-size:13px; color:#cbd5e1; margin-bottom:14px; line-height:1.5;">
          Pembantu pintar kabin terapung di atas semua aplikasi (Waze, Spotify, Kamera 360). Boleh dikawal dengan <strong>mengetik ikon terapung ⚡ di skrin</strong> atau <strong>tekan 2x laju butang Mic pada stereng</strong>.
        </p>
        <div style="display:flex; gap:10px; flex-wrap:wrap;">
          <button type="button" class="primary" onclick="toggleFloatingAssistantDirect()" style="font-size:12px; padding:8px 16px; background:#00E5FF; color:#0f172a; font-weight:700; border-radius:8px; border:none; cursor:pointer;">
            🎈 Buka / Pindah Floating Bubble
          </button>
          <button type="button" class="secondary" onclick="testJarvisVoiceDirect()" style="font-size:12px; padding:8px 16px; border-radius:8px; cursor:pointer;">
            🔊 Uji Suara Kabin
          </button>
        </div>
      </section>
      <section class="card control-card">
        ${icon('power_btn')}
        <h2>High Voltage Power</h2>
        <button class="secondary" data-action="power">${state.power ? 'Power Down System…' : 'Awaken System'}</button>
      </section>
    </div>`;
}

const renders = {
  overview,
  battery: batteryPage,
  power: powerPage,
  trip: tripPage,
  climate: climatePage,
  controls: controlsPage,
  megafon: () => window.MegafonUI ? window.MegafonUI.render() : '<p>Memuatkan Megafon...</p>'
};

const titles = {
  overview: ['KOKPIT', 'Status kenderaan'],
  battery: ['01 / HV BATTERY', 'Bateri voltan tinggi'],
  power: ['02 / POWER FLOW', 'Aliran kuasa bateri'],
  trip: ['03 / TRIP', 'Trip komputer kereta'],
  climate: ['04 / CLIMATE', 'Cabin Climate Control'],
  controls: ['05 / CONTROLS', 'Quick Controls'],
  megafon: ['06 / MEGAFON LUAR', 'Pusat Kawalan Suara & Speaker Bumper']
};

function render() {
  let page = location.hash.slice(1) || 'overview';
  if (!renders[page] && !['overview', 'battery', 'power'].includes(page)) page = 'overview';
  document.getElementById('main').dataset.page = page;
  const statusPill = document.getElementById('status-pill');
  if (statusPill) statusPill.innerHTML = `<i></i> ${window.__EMAS_MOCK__ ? 'SIMULASI LAPTOP' : state.isLive ? 'DATA IHU' : 'MENUNGGU DATA'}`;
  document.getElementById('main').style.setProperty('--charge', `${Math.max(0, Math.min(100, state.soc))}%`);
  
  document.getElementById('navigation').innerHTML = pages.map(([id, label]) => `
    <a href="#${id}" class="${id === page ? 'active' : ''}" ${id === page ? 'aria-current="page"' : ''}>
      ${icon(id)}
      <span>${label}</span>
    </a>
  `).join('');

  const t = titles[page];
  const modeBadge = window.__EMAS_MOCK__ ? 'SIMULASI LAPTOP · TIADA KERETA' : state.isLive ? 'BACAAN TERAKHIR' : 'TIADA DATA KERETA';

  document.getElementById('main').innerHTML = `
    <div class="page-heading">
      <div>
        <div class="eyebrow">${t[0]}</div>
        <h1>${t[1]}</h1>
      </div>
      <span class="pill"><i class="connection-dot"></i>${modeBadge}</span>
    </div>
    ${page === 'overview' ? overviewCockpitPage() : page === 'battery' ? hvBatteryPage() : page === 'power' ? powerFlowPage() : renders[page] ? renders[page]() : ''}`;
  if (page === 'controls' && window.LockSoundUI) window.LockSoundUI.mount(document.getElementById('lock-sound-panel'));
  if (page === 'megafon' && window.MegafonUI) window.MegafonUI.mount();
  // These legacy bridge methods are explicitly blocked by the current Android build.
  for (const action of ['camp','vent','power','fan-off','set-air','cooler','warmer','temp-preset','fan-down','fan-up']) {
    document.querySelectorAll(`[data-action="${action}"]`).forEach(button => {
      button.disabled = true; button.title = 'Kawalan belum tersedia dalam kontrak daemon semasa.';
    });
  }
  document.querySelectorAll('#setpoint-slider,#fan-slider').forEach(input => { input.disabled = true; });
  if (window.CompanionConnection && window.CompanionConnection.client) {
    document.querySelectorAll('[data-action="set-avas"],[data-action="apply-preset"],[data-action="open-usb-modal"],[data-action="save-tuning"],[data-action="tuning-slider"]').forEach(button => {
      button.disabled = true; button.title = 'Pengurusan AVAS tersedia melalui aplikasi IHU.';
    });
  }
  if (typeof drawMotorGraph === 'function') drawMotorGraph();
}
let toastTimer;
function toast(s) {
  const e = document.getElementById('toast');
  e.textContent = s;
  e.classList.add('visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => e.classList.remove('visible'), 3000);
}

let windowCommandBusy = false;
let bootCommandBusy = false;
document.addEventListener('click', async (e) => {
  const b = e.target.closest('[data-action]');
  if (!b || b.disabled) return;
  const a = b.dataset.action;
  if (['window-crack','window-close','open-frunk','toggle-trunk'].includes(a) && !canControlVehicle()) { toast('Bacaan sambungan sudah luput. Tunggu kereta tersambung semula.'); return; }
  if (a === 'window-crack' || a === 'window-close') {
    if (windowCommandBusy) return;
    windowCommandBusy = true;
    const output = document.getElementById('window-command-status');
    const controls = Array.from(document.querySelectorAll('[data-action^="window-"]'));
    controls.forEach(button => { button.disabled = true; });
    if (output) output.textContent = 'Menghantar arahan…';
    try {
      const response = await vehicleFetch('/api/window?action=' + (a === 'window-close' ? 'close' : 'crack'));
      const data = await response.json();
      if (!response.ok || data.ok !== true) throw new Error('DAEMON_REJECTED');
      if (output) output.textContent = 'Arahan dihantar. Semak kedudukan cermin pada kereta.';
      toast('Arahan cermin dihantar. Kedudukan belum disahkan.');
    } catch (error) {
      if (output) output.textContent = 'Tiada pengesahan. Arahan mungkin telah dihantar; semak kereta sebelum mencuba lagi.';
      toast('Cermin: semak sambungan dan keadaan kereta.');
    } finally { windowCommandBusy = false; updateControlAvailability(); }
    return;
  }
  if (a === 'open-frunk') {
    if (bootCommandBusy) return;
    if (confirm('Buka bonet depan (Frunk)? Sila pastikan keselamatan kenderaan.')) {
      bootCommandBusy = true; b.disabled = true;
      toast('Menghantar arahan buka Frunk...');
      try {
        const res = await vehicleFetch('/api/frunk/open');
        if (res.ok) {
          toast('Arahan frunk dihantar. Menunggu bacaan sensor.');
        } else {
          toast('Gagal membuka bonet depan');
        }
      } catch (err) {
        toast('Ralat: ' + err.message);
      } finally { bootCommandBusy = false; b.disabled = false; }
    }
    return;
  }
  if (a === 'toggle-trunk') {
    if (bootCommandBusy) return;
    bootCommandBusy = true; b.disabled = true;
    const isCurrentlyOpen = state.doors && state.doors.trunk;
    const actionName = isCurrentlyOpen ? 'menutup' : 'membuka';
    toast(`Menghantar arahan ${actionName} but belakang...`);
    try {
      const res = await vehicleFetch('/api/trunk/toggle');
      if (res.ok) {
        toast(`✓ Arahan ${actionName} but dihantar`);
      } else {
        toast('Gagal mengawal but');
      }
    } catch (err) {
      toast('Ralat: ' + err.message);
    } finally { bootCommandBusy = false; b.disabled = false; }
    return;
  }
  if (a === 'vent') {
    state.vent = !state.vent;
    const targetPercent = state.vent ? 7 : 0;
    render();
    toast(state.vent ? 'Membuka tingkap 7% (Heat Vent)...' : 'Menutup tingkap rapat...');
    vehicleFetch((state.vent ? '/api/ventilate?minutes=30' : '/api/close')).catch(() => {});
    try {
      if (window.AndroidEnergy && typeof window.AndroidEnergy.triggerWindowCrack === 'function') {
        window.AndroidEnergy.triggerWindowCrack(targetPercent);
      }
    } catch (e) {}
    return;
  }
  if (a === 'camp') {
    state.camp = !state.camp;
    render();
    toast(state.camp ? 'Mod Perkhemahan: Diaktifkan' : 'Mod Perkhemahan: Dinyahaktifkan');
    return;
  } else if (a === 'fan-off') {
    state.fanOff = !state.fanOff;
    if (!state.fanOff && state.fan < 1) state.fan = 1;
    render();
    toast(state.fanOff ? 'Blower Dimatikan (A/C OFF)' : `Blower Dihidupkan · Aras ${state.fan}`);
    dispatchHvacPower(!state.fanOff);
  } else if (a === 'set-air') {
    state.airMode = Number(b.dataset.air);
    render();
    toast(`Mod Edaran Udara: ${['Udara Luar (Fresh Air)', 'Kitaran Dalam (Recirc)', 'Penyahkabus (Defog)'][state.airMode]}`);
    dispatchHvacRecirc(state.airMode);
  } else if (a === 'set-avas') {
    const mode = Number.parseInt(b.dataset.avas, 10);
    if (!Number.isInteger(mode) || mode < 0 || mode > 3) return;
    const labels = ['Senyap / Mute (Off)', 'Slot 1', 'Slot 2', 'Slot 3'];
    b.disabled = true;
    try {
      const response = await vehicleFetch(`/api/avas?mode=${mode}`, { method: 'POST' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      state.avas = mode;
      if (mode > 0) state.avasSlot = mode;
      render();
      toast(`Profil AVAS: ${labels[mode]}`);
    } catch (err) {
      b.disabled = false;
      toast('AVAS gagal dikemas kini. Cuba semula.');
    }
  } else if (a === 'apply-preset') {
    const pr = b.dataset.preset;
    window.applyPresetDirect(pr);
  } else if (a === 'open-usb-modal') {
    window.openUsbModal();
  } else if (a === 'save-tuning') {
    window.saveAvasTuningDirect(false);
  } else if (a === 'power') {
    if (state.power) document.getElementById('confirm').showModal();
    else {
      state.power = true;
      render();
      toast('Sistem voltan tinggi kenderaan diaktifkan');
    }
  } else if (a === 'cooler') {
    state.setpoint = Math.max(17, Math.round(state.setpoint) - 1);
    const v = document.getElementById('setpoint-value');
    if (v) v.textContent = formatSetpoint(state.setpoint);
    const s = document.getElementById('setpoint-slider');
    if (s) s.value = state.setpoint;
    document.querySelectorAll('.climate-presets .preset-pill').forEach(p => {
      p.classList.toggle('active', Number(p.dataset.temp) === state.setpoint);
    });
    dispatchHvacSetpoint(state.setpoint, true);
    toast(`Suhu: ${formatSetpoint(state.setpoint)}`);
  } else if (a === 'warmer') {
    state.setpoint = Math.min(24, Math.round(state.setpoint) + 1);
    const v = document.getElementById('setpoint-value');
    if (v) v.textContent = formatSetpoint(state.setpoint);
    const s = document.getElementById('setpoint-slider');
    if (s) s.value = state.setpoint;
    document.querySelectorAll('.climate-presets .preset-pill').forEach(p => {
      p.classList.toggle('active', Number(p.dataset.temp) === state.setpoint);
    });
    dispatchHvacSetpoint(state.setpoint, true);
    toast(`Suhu: ${formatSetpoint(state.setpoint)}`);
  } else if (a === 'temp-preset') {
    state.setpoint = Number(b.dataset.temp);
    const v = document.getElementById('setpoint-value');
    if (v) v.textContent = `${Math.round(state.setpoint)}°`;
    const s = document.getElementById('setpoint-slider');
    if (s) s.value = state.setpoint;
    document.querySelectorAll('.climate-presets .preset-pill').forEach(p => {
      p.classList.toggle('active', Number(p.dataset.temp) === state.setpoint);
    });
    dispatchHvacSetpoint(state.setpoint, true);
    toast(`Suhu Sasaran: ${Math.round(state.setpoint)}°C`);
  } else if (a === 'fan-down') {
    state.fan = Math.max(1, state.fan - 1);
    state.fanOff = false;
    const v = document.getElementById('fan-value');
    if (v) v.textContent = state.fan;
    const s = document.getElementById('fan-slider');
    if (s) { s.value = state.fan; s.disabled = false; }
    dispatchHvacFan(state.fan, true);
    toast(`Kelajuan Blower: Aras ${state.fan}`);
  } else if (a === 'fan-up') {
    state.fan = Math.min(8, state.fan + 1);
    state.fanOff = false;
    const v = document.getElementById('fan-value');
    if (v) v.textContent = state.fan;
    const s = document.getElementById('fan-slider');
    if (s) { s.value = state.fan; s.disabled = false; }
    dispatchHvacFan(state.fan, true);
    toast(`Kelajuan Blower: Aras ${state.fan}`);
  }
});

let userInteractingHvacUntil = 0;
let hvacTempTimer = null;
function dispatchHvacSetpoint(temp, immediate = false) {
  dispatchHvacZone('L', temp, immediate);
}

function dispatchHvacZone(side, temp, immediate = false) {
  userInteractingHvacUntil = Date.now() + 2500;
  clearTimeout(hvacTempTimer);
  const key = side === 'R' ? 'tempR' : 'tempL';
  const actuate = () => {
    try {
      if (window.AndroidEnergy) {
        if (side === 'R' && typeof window.AndroidEnergy.setPassengerTemperature === 'function') {
          window.AndroidEnergy.setPassengerTemperature(temp);
        } else if (side !== 'R' && typeof window.AndroidEnergy.setDriverTemperature === 'function') {
          window.AndroidEnergy.setDriverTemperature(temp);
        }
      }
    } catch (e) {}
    vehicleFetch('/api/hvac?' + key + '=' + temp).catch(() => {});
  };
  if (immediate) actuate();
  else hvacTempTimer = setTimeout(actuate, 80);
}

let hvacFanTimer = null;
function dispatchHvacFan(fan, immediate = false) {
  userInteractingHvacUntil = Date.now() + 2500;
  clearTimeout(hvacFanTimer);
  const actuate = () => {
    try {
      if (window.AndroidEnergy && typeof window.AndroidEnergy.setBlowerSpeed === 'function') {
        window.AndroidEnergy.setBlowerSpeed(fan);
      }
    } catch (e) {}
    vehicleFetch('/api/hvac?fan=' + fan).catch(() => {});
  };
  if (immediate) actuate();
  else hvacFanTimer = setTimeout(actuate, 80);
}

function dispatchHvacPower(on) {
  userInteractingHvacUntil = Date.now() + 2500;
  try {
    if (window.AndroidEnergy && typeof window.AndroidEnergy.setClimatePower === 'function') {
      window.AndroidEnergy.setClimatePower(on);
    }
  } catch (e) {}
  vehicleFetch('/api/hvac?power=' + (on ? 1 : 0)).catch(() => {});
}

function dispatchHvacRecirc(mode) {
  userInteractingHvacUntil = Date.now() + 2500;
  try {
    if (window.AndroidEnergy && typeof window.AndroidEnergy.setAirRecirculation === 'function') {
      window.AndroidEnergy.setAirRecirculation(mode);
    }
  } catch (e) {}
  vehicleFetch('/api/hvac?recirc=' + mode).catch(() => {});
}

document.addEventListener('change', (e) => {
  if (e.target.disabled) return;
  const slider = e.target.closest('[data-action="tuning-slider"]');
  if (slider) {
    clearTimeout(tuningDebounceTimer);
    window.saveAvasTuningDirect(true);
    return;
  }
  if (e.target.id === 'setpoint-slider') {
    dispatchHvacZone('L', Number(e.target.value));
    return;
  }
  if (e.target.id === 'fan-slider') {
    dispatchHvacFan(Number(e.target.value));
    return;
  }
});

document.addEventListener('input', (e) => {
  if (e.target.disabled) return;
  const slider = e.target.closest('[data-action="tuning-slider"]');
  if (slider) {
    const idx = parseInt(slider.dataset.index, 10);
    const val = parseInt(slider.value, 10);
    const slot = state.avasSlot || 3;
    if (state.avasConfig[slot]) {
      state.avasConfig[slot].volume[idx] = val;
      const valBadge = document.getElementById(`speed-val-${idx}`);
      if (valBadge) valBadge.textContent = val;
      const svgWrap = document.getElementById('avas-svg-wrap');
      if (svgWrap) svgWrap.innerHTML = renderAvasSvgCurve(state.avasConfig[slot].volume);
      scheduleAutoApplyTuning();
    }
    return;
  }
  if (e.target.id === 'setpoint-slider') {
    state.setpoint = Math.min(24, Math.max(17, Number(e.target.value)));
    const value = document.getElementById('setpoint-value');
    if (value) value.textContent = formatSetpoint(state.setpoint);
    dispatchHvacZone('L', state.setpoint);
  }
  if (e.target.id === 'fan-slider') {
    state.fan = Number(e.target.value);
    state.fanOff = false;
    const value = document.getElementById('fan-value');
    if (value) value.textContent = state.fan;
    e.target.setAttribute('aria-valuetext', `Aras ${state.fan} daripada 8`);
    dispatchHvacFan(state.fan);
  }
});

document.getElementById('confirm').addEventListener('close', (e) => {
  if (e.target.returnValue === 'confirm') {
    state.power = false;
    state.camp = false;
    render();
    toast('Arahan kuasa tidak tersedia; tiada perubahan dihantar ke kereta.');
  }
});

function theme(value) {
  document.documentElement.dataset.theme = value;
  const b = document.getElementById('theme');
  if (b) {
    b.textContent = value === 'light' ? '☾' : '☀';
    b.setAttribute('aria-label', value === 'light' ? 'Tukar ke mod malam' : 'Tukar ke mod siang');
  }
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = value === 'light' ? '#eef0ed' : '#111814';
  try {
    localStorage.setItem('emas-theme', value);
  } catch {}
}

let savedTheme;
try {
  savedTheme = localStorage.getItem('emas-theme');
} catch {}

if (!savedTheme) savedTheme = 'dark';
theme(savedTheme === 'dark' ? 'dark' : 'light');

document.getElementById('theme').onclick = () => {
  theme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light');
};

function clock() {
  const el = document.getElementById('clock');
  if (el) {
    el.textContent = new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
  }
}
clock();
let clockTimer = null;

window.addEventListener('hashchange', () => {
  render();
  window.scrollTo(0, 0);
});

// Telemetry Polling & Live DOM Engine
function updateLiveTelemetryDom() {
  if (typeof drawMotorGraph === 'function') drawMotorGraph();
}
function clearLiveTelemetry() {
  telemetryObservedAt = 0;
  state.isLive = false; state.vehicleTelemetryAvailable = false;
  batteryReading = {soc:null,voltage:null,current:null,observedAt:null};
  state.speed = null; state.gear = null; state.motorPower = null; state.ambientTemp = null;
  state.range = null; state.battTemp = null; state.hvacPower = null; state.auxPower = null;
  state.doorKnown = {fl:false,fr:false,rl:false,rr:false,trunk:false,frunk:false};
  state.doorDataAvailable = false; state.tpmsAvailable = false;
  state.hvacDataAvailable = false;
  state.seatbelt = {driver:null,passenger:null};
  state.trip = {distanceKm:null,durationMin:null,avgSpeedKmh:null,consumptionKwh100:null,energyKwh:null,distance2Km:null};
  if (typeof window.updateLiveMotorPower === 'function') window.updateLiveMotorPower(null);
}
function processIncomingEnergy(data) {
  const sample = CompanionTelemetry.normalize(data);
  telemetryObservedAt = Date.now();
  batteryReading = sample.battery;
  state.isLive = [sample.battery.soc,sample.battery.voltage,sample.battery.current].some(Number.isFinite);
  state.isSimulation = !!window.__EMAS_MOCK__;
  if (sample.battery.soc !== null) state.soc = sample.battery.soc;
  if (sample.battery.voltage !== null) state.voltage = sample.battery.voltage;
  if (sample.battery.current !== null) state.current = sample.battery.current;
  for (const key of ['speed','gear','ambientTemp','range','battTemp','motorPower','hvacPower','auxPower','seatbelt','trip']) state[key] = sample[key];
  state.doors = sample.doors; state.doorKnown = sample.known;
  state.doorDataAvailable = Object.values(sample.known).some(Boolean);
  state.vehicleTelemetryAvailable = state.doorDataAvailable || sample.speed !== null || sample.gear !== null;
  state.hvacDataAvailable = sample.hvac.setpoint !== null && sample.hvac.fan !== null;
  if (state.hvacDataAvailable) {
    state.setpoint = sample.hvac.setpoint; state.fan = sample.hvac.fan;
    if (sample.hvac.powerOn !== null) state.fanOff = !sample.hvac.powerOn;
    if (sample.hvac.airMode !== null) state.airMode = sample.hvac.airMode;
  }
  if (typeof window.updateLiveMotorPower === 'function') window.updateLiveMotorPower(sample.motorPower,!state.isSimulation,sample.powerKind);
}
async function pollTelemetry(isCurrent) {
  if (!vehicleTransport.available()) {
    if (isCurrent()) { clearLiveTelemetry(); render(); }
    return false;
  }
  try {
    const response = await vehicleFetch('/api/energy');
    if (!isCurrent()) return false;
    if (!response.ok) throw new Error('CAR_OFFLINE');
    processIncomingEnergy(await response.json());
    updateControlAvailability();
    if (window.CompanionConnection) window.CompanionConnection.observe(true);
    const page = document.getElementById('main').dataset.page;
    if (['overview','battery','power','trip'].includes(page)) render();
    return true;
  } catch (error) {
    if (!isCurrent()) return false;
    clearLiveTelemetry();
    updateControlAvailability();
    if (window.CompanionConnection) window.CompanionConnection.observe(false);
    const page = document.getElementById('main').dataset.page;
    if (['overview','battery','power','trip'].includes(page)) render();
    return false;
  }
}
const telemetryPoller = CompanionClient.createPoller(pollTelemetry,{interval:1200});
window.CompanionLifecycle = {
  pause() {
    window.CompanionPaused = true;
    telemetryPoller.pause(); clearInterval(clockTimer); clockTimer = null;
    for (const timer of [toastTimer,tuningDebounceTimer,hvacTempTimer,hvacFanTimer]) clearTimeout(timer);
    if (window.CompanionConnection && window.CompanionConnection.client) window.CompanionConnection.client.pause();
    if (window.LockSoundUI) window.LockSoundUI.pause();
    clearLiveTelemetry(); render();
  },
  resume() {
    if (document.hidden) return;
    window.CompanionPaused = false;
    if (window.CompanionConnection && window.CompanionConnection.client) window.CompanionConnection.client.resume();
    clock(); if (!clockTimer) clockTimer = setInterval(clock,30000);
    telemetryPoller.resume();
    if (window.LockSoundUI) window.LockSoundUI.resume();
  }
};
document.addEventListener('visibilitychange',() => window.CompanionLifecycle[document.hidden ? 'pause' : 'resume']());
window.addEventListener('pagehide',() => window.CompanionLifecycle.pause());
window.addEventListener('pageshow',() => window.CompanionLifecycle.resume());
window.addEventListener('offline',() => window.CompanionLifecycle.pause());
window.addEventListener('online',() => window.CompanionLifecycle.resume());
window.addEventListener('companion-connected',() => { render(); telemetryPoller.refresh(); });

// Android Automotive OS Native Bridge Callbacks
window.onEnergyUpdate = function(data) {
  if (document.hidden) return;
  try { processIncomingEnergy(data); render(); } catch (_) { clearLiveTelemetry(); render(); }
};

window.onCarNightModeChanged = function(isNight) {
  theme(isNight ? 'dark' : 'light');
};

window.toggleFloatingAssistantDirect = function() {
  if (window.AndroidEnergy && window.AndroidEnergy.startFloatingBubble) {
    window.AndroidEnergy.startFloatingBubble();
    toast("Floating Bubble diaktifkan di skrin! 🎈");
  } else if (window.Android && window.Android.startFloatingBubble) {
    window.Android.startFloatingBubble();
    toast("Floating Bubble diaktifkan di skrin! 🎈");
  } else {
    toast("Antara muka Android tidak dikesan");
  }
};

window.testJarvisVoiceDirect = function() { toast('Ujian suara tidak tersedia dalam pratonton ini.'); };

render();
window.CompanionLifecycle.resume();
