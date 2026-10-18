/**
 * Proton e.MAS 5 — Luxury Cockpit Runtime Engine
 * Real-time CAN bus telemetry, Split-Screen Hub & PiP Dock, and Smart Climate
 */
(function() {
  'use strict';

  const $ = id => document.getElementById(id);
  const simulationMode = new URLSearchParams(window.location.search).get('mock') === '1';
  const sample = value => simulationMode ? value : null;
  const vehicleClient = window.SafeVehicle.createClient(window);
  const telemetryNow = window.SafeVehicle.clock;
  const hasNumber = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value));

  const state = {
    isLive: false,
    isSimulated: simulationMode,
    lastTelemetryAt: 0,
    telemetryAvailable: false,
    soc: sample(85.0),
    voltage: sample(365.4),
    current: sample(38.8),
    powerKw: sample(14.2),
    remainingKwh: sample(52.5),
    rangeKm: sample(340),
    gear: sample('P'),
    speed: sample(0),
    ambientTemp: sample(32.0),
    battTemp: sample(31.5),
    doors: { fl: false, fr: false, rl: false, rr: false, frunk: false, trunk: false },
    tripKm: sample(0),
    autoClimateEnabled: sample(true),
    autoClimateMode: sample('adaptive'),
    setpoint: sample(22.0),
    fan: sample(3),
    fanOff: false,
    activeSplitTab: 'power',
    soh: sample(99.8),
    cellDeltaMv: sample(11),
    cellVAvg: sample(3.287),
    cellVMax: sample(3.292),
    cellVMin: sample(3.281),
    cellsHealthy: 120,
    cellsDead: 0,
    peakTractionKw: 0.0,
    peakRegenKw: 0.0,
    selectedCellId: 1
  };

  // Live Canvas Graph State (50 points history)
  const graphHistory = [];
  const maxPoints = 50;
  for (let i = 0; i < maxPoints; i++) {
    // Realistic EV waveform with both traction (+kW) and recuperation (-kW)
    const val = simulationMode ? (Math.sin(i * 0.28) * 32.0 + Math.cos(i * 0.6) * 12.0) : null;
    graphHistory.push(val);
  }

  function drawGraph() {
    const canvas = $('energyCanvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;

    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.scale(dpr, dpr);

    const w = rect.width;
    const h = rect.height;
    const midY = h * 0.50; // Centered 0 kW baseline for +/- dual flow

    ctx.clearRect(0, 0, w, h);

    // Subtle Grid Background lines (+40kW, +20kW, 0kW, -20kW, -40kW)
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 4]);

    // +40 kW Traction Guide
    const yP40 = midY - (40 / 70) * (midY - 15);
    ctx.strokeStyle = 'rgba(56, 189, 248, 0.12)';
    ctx.beginPath(); ctx.moveTo(0, yP40); ctx.lineTo(w, yP40); ctx.stroke();

    // -25 kW Regen Guide
    const yN25 = midY - (-25 / 70) * (midY - 15);
    ctx.strokeStyle = 'rgba(16, 185, 129, 0.15)';
    ctx.beginPath(); ctx.moveTo(0, yN25); ctx.lineTo(w, yN25); ctx.stroke();

    // 0 kW Baseline (Solid crisp line with glow)
    ctx.setLineDash([]);
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.35)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(0, midY);
    ctx.lineTo(w, midY);
    ctx.stroke();

    // Draw baseline label
    ctx.fillStyle = 'rgba(255, 255, 255, 0.4)';
    ctx.font = '10px monospace';
    ctx.fillText('0 kW', 6, midY - 4);
    ctx.fillText('+60 kW', 6, 16);
    ctx.fillText('−35 kW', 6, h - 14);

    const validPoints = graphHistory.map((value, index) => ({ value, index })).filter(point => hasNumber(point.value));
    if (validPoints.length === 0) return;

    const stepX = w / (maxPoints - 1);

    // Draw positive gradient area (Traction > 0)
    ctx.beginPath();
    validPoints.forEach(({ value, index }, ptIdx) => {
      const x = index * stepX;
      const kw = Math.max(0, Number(value));
      const y = midY - (kw / 70) * (midY - 15);
      if (ptIdx === 0) ctx.moveTo(x, midY);
      ctx.lineTo(x, y);
      if (ptIdx === validPoints.length - 1) ctx.lineTo(x, midY);
    });
    ctx.closePath();
    const gradTraction = ctx.createLinearGradient(0, 0, 0, midY);
    gradTraction.addColorStop(0, 'rgba(56, 189, 248, 0.35)');
    gradTraction.addColorStop(1, 'rgba(56, 189, 248, 0.0)');
    ctx.fillStyle = gradTraction;
    ctx.fill();

    // Draw negative gradient area (Regen < 0)
    ctx.beginPath();
    validPoints.forEach(({ value, index }, ptIdx) => {
      const x = index * stepX;
      const kw = Math.min(0, Number(value));
      const y = midY - (kw / 70) * (midY - 15);
      if (ptIdx === 0) ctx.moveTo(x, midY);
      ctx.lineTo(x, y);
      if (ptIdx === validPoints.length - 1) ctx.lineTo(x, midY);
    });
    ctx.closePath();
    const gradRegen = ctx.createLinearGradient(0, midY, 0, h);
    gradRegen.addColorStop(0, 'rgba(16, 185, 129, 0.0)');
    gradRegen.addColorStop(1, 'rgba(16, 185, 129, 0.35)');
    ctx.fillStyle = gradRegen;
    ctx.fill();

    // Draw the continuous power waveform line
    ctx.beginPath();
    ctx.lineWidth = 2.5;
    validPoints.forEach(({ value, index }, ptIdx) => {
      const x = index * stepX;
      const clampedKw = Math.max(-40, Math.min(80, Number(value)));
      const y = midY - (clampedKw / 70) * (midY - 15);
      if (ptIdx === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });

    const strokeGrad = ctx.createLinearGradient(0, 0, 0, h);
    strokeGrad.addColorStop(0, '#38bdf8'); // Cyan for peak traction
    strokeGrad.addColorStop(0.5, '#f1f5f9'); // White at 0 kW
    strokeGrad.addColorStop(1, '#10b981'); // Emerald for regen
    ctx.strokeStyle = strokeGrad;
    ctx.stroke();

    // Head pulse indicator dot
    const lastPoint = validPoints[validPoints.length - 1];
    const lastKw = Number(lastPoint.value);
    const lastX = lastPoint.index * stepX;
    const lastY = midY - (Math.max(-40, Math.min(80, lastKw)) / 70) * (midY - 15);
    ctx.beginPath();
    ctx.arc(lastX, lastY, 4.5, 0, Math.PI * 2);
    ctx.fillStyle = lastKw < -0.1 ? '#10b981' : '#38bdf8';
    ctx.shadowBlur = 8;
    ctx.shadowColor = lastKw < -0.1 ? '#10b981' : '#38bdf8';
    ctx.fill();
    ctx.shadowBlur = 0;
  }

  // Generate and render 120 cells matrix
  function renderCellsGrid() {
    const container = $('cells-grid-container');
    if (!container) return;

    const baseV = hasNumber(state.cellVAvg) ? Number(state.cellVAvg) : (hasNumber(state.voltage) ? Number(state.voltage) / 120 : 3.287);
    const delta = hasNumber(state.cellDeltaMv) ? Number(state.cellDeltaMv) : 11;

    let html = '';
    for (let i = 1; i <= 120; i++) {
      // Small simulated pseudo-variance per cell centered around baseV
      // All cells are healthy Aegis Short Blade LFP cells
      const varianceMv = ((Math.sin(i * 1.7) * (delta / 2.0))).toFixed(0);
      const cellV = (baseV + (varianceMv / 1000.0)).toFixed(3);
      const isSelected = state.selectedCellId === i;
      
      let borderStyle = 'border-white/10';
      let dotColor = 'bg-emerald-400';
      let valColor = 'text-white';
      if (Math.abs(varianceMv) > 25) {
        borderStyle = 'border-amber-500/40';
        dotColor = 'bg-amber-400';
        valColor = 'text-amber-300';
      }

      html += `<button onclick="window.selectBatteryCell(${i}, ${cellV}, ${varianceMv})" class="${isSelected ? 'selected-cell' : borderStyle}">
        <div class="flex items-center justify-between text-[9px] font-mono leading-none w-full">
          <span class="text-white/40 font-bold">#${i}</span>
          <span class="w-1.5 h-1.5 rounded-full ${dotColor}"></span>
        </div>
        <span class="text-[11px] font-mono font-bold ${valColor} leading-none">${cellV}V</span>
      </button>`;
    }
    container.innerHTML = html;
  }

  window.selectBatteryCell = function(id, v, devMv) {
    state.selectedCellId = id;
    const moduleIdx = Math.floor((id - 1) / 15) + 1;
    if ($('cell-detail-id')) $('cell-detail-id').textContent = `Sel #${id} (Modul ${moduleIdx})`;
    if ($('cell-detail-v')) $('cell-detail-v').textContent = `${Number(v).toFixed(3)} V`;
    if ($('cell-detail-dev')) $('cell-detail-dev').textContent = `${devMv >= 0 ? '+' : ''}${devMv} mV`;
    renderCellsGrid();
  };

  function toast(msg) {
    const el = $('toast');
    if (!el) return;
    el.textContent = msg;
    el.classList.remove('opacity-0', 'pointer-events-none');
    el.classList.add('opacity-100');
    clearTimeout(window.tTimer);
    window.tTimer = setTimeout(() => {
      el.classList.remove('opacity-100');
      el.classList.add('opacity-0', 'pointer-events-none');
    }, 2500);
  }
  window.toast = toast;

  // Split-Screen Hub Tab Switcher
  window.switchSplitTab = function(tab) {
    if (tab === 'hub') tab = 'power';
    state.activeSplitTab = tab;
    ['power', 'health', 'cells'].forEach(t => {
      const btn = $('tab-btn-' + t);
      const content = $('tab-content-' + t);
      if (btn && content) {
        if (t === tab) {
          btn.className = 'px-4 py-1.5 rounded-lg bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 font-bold transition-all flex items-center gap-2 shadow-sm';
          content.classList.remove('hidden');
        } else {
          btn.className = 'px-4 py-1.5 rounded-lg text-white/50 hover:text-white transition-all flex items-center gap-2 font-semibold border border-transparent';
          content.classList.add('hidden');
        }
      }
    });
    if (tab === 'power') {
      setTimeout(drawGraph, 50);
    } else if (tab === 'cells') {
      renderCellsGrid();
    }
  };

  // Map Search / Navigation
  window.searchMapLocation = function(query) {
    toast(`Membuka peta bagi carian: "${query}" 🗺️`);
    window.openExternalApp('maps');
  };

  // YouTube / Media Player Loader
  window.loadYouTubeVideo = function(videoId) {
    toast('Melancarkan SmartTube... 📺');
    window.openExternalApp('smarttube');
  };

  window.loadYouTubeSearch = function(query) {
    toast(`Cari "${query}" di SmartTube 📺`);
    window.openExternalApp('smarttube');
  };

  window.searchYouTube = function() {
    window.openExternalApp('smarttube');
  };

  // External Native App Launcher (SmartTube / Netflix / Proton Original Navigation)
  window.openExternalApp = function(appType) {
    if (appType === 'smarttube') {
      if (window.Android && window.Android.launchApp) {
        window.Android.launchApp('org.smarttube.stable');
      } else {
        window.open('https://youtube.com', '_blank');
      }
      toast('Melancarkan SmartTube EV... ▶');
    } else if (appType === 'netflix') {
      if (window.Android && window.Android.launchApp) {
        window.Android.launchApp('com.netflix.mediaclient');
      } else {
        window.open('https://netflix.com', '_blank');
      }
      toast('Melancarkan Netflix... 🎬');
    } else if (appType === 'maps' || appType === 'proton_map') {
      if (window.Android && window.Android.launchApp) {
        window.Android.launchApp('com.neusoft.na.navigation');
      } else {
        window.open('https://maps.google.com', '_blank');
      }
      toast('Melancarkan Peta Proton (Original)... 🧭');
    }
  };

  // Draggable Split-Screen Engine
  (function initSplitResizer() {
    function setup() {
      const divider = $('split-divider');
      const mediaPanel = $('cockpit-media');
      const telemetryPanel = $('cockpit-telemetry');
      const container = $('cockpit-grid');
      if (!divider || !mediaPanel || !telemetryPanel || !container) return;

      let isDragging = false;
      let startX = 0;
      let startMediaWidth = 0;

      window.setSplitRatio = function(ratio) {
        if (state.drawerMode && typeof window.toggleEdgeDrawerMode === 'function') {
          window.toggleEdgeDrawerMode(false);
        }
        const r = parseFloat(ratio);
        if (r >= 0.96) {
          mediaPanel.style.setProperty('flex', '1 0 100%', 'important');
          mediaPanel.style.setProperty('max-width', '100%', 'important');
          telemetryPanel.style.setProperty('display', 'none', 'important');
          divider.style.setProperty('display', 'none', 'important');
        } else {
          telemetryPanel.style.setProperty('display', 'flex', 'important');
          divider.style.setProperty('display', 'flex', 'important');
          const mediaPct = Math.max(35, Math.min(88, Math.round(r * 100)));
          const telePct = 100 - mediaPct;
          mediaPanel.style.setProperty('flex', `${mediaPct} 1 0px`, 'important');
          mediaPanel.style.setProperty('max-width', `${mediaPct}%`, 'important');
          telemetryPanel.style.setProperty('flex', `${telePct} 1 0px`, 'important');
          telemetryPanel.style.setProperty('max-width', `${telePct}%`, 'important');
        }
        localStorage.setItem('emas_split_ratio', r.toString());
        if (typeof drawGraph === 'function') setTimeout(drawGraph, 80);
      };

      function getCoordX(e) {
        if (e.touches && e.touches.length > 0) return e.touches[0].clientX;
        if (e.changedTouches && e.changedTouches.length > 0) return e.changedTouches[0].clientX;
        return e.clientX;
      }

      function onPointerDown(e) {
        isDragging = true;
        startX = getCoordX(e);
        startMediaWidth = mediaPanel.getBoundingClientRect().width;
        divider.classList.add('dragging');
        document.body.style.cursor = 'col-resize';
        if (e.cancelable) e.preventDefault();
      }

      function onPointerMove(e) {
        if (!isDragging) return;
        const clientX = getCoordX(e);
        if (clientX === undefined || clientX === null) return;
        const totalWidth = container.clientWidth;
        const newMediaWidth = startMediaWidth + (clientX - startX);
        const ratio = newMediaWidth / totalWidth;
        if (ratio >= 0.90) {
          window.setSplitRatio(1.0);
        } else if (ratio <= 0.35) {
          window.setSplitRatio(0.35);
        } else {
          window.setSplitRatio(ratio);
        }
        if (e.cancelable) e.preventDefault();
      }

      function onPointerUp() {
        if (!isDragging) return;
        isDragging = false;
        divider.classList.remove('dragging');
        document.body.style.cursor = '';
      }

      divider.addEventListener('mousedown', onPointerDown);
      divider.addEventListener('touchstart', onPointerDown, { passive: false });
      window.addEventListener('mousemove', onPointerMove);
      window.addEventListener('touchmove', onPointerMove, { passive: false });
      window.addEventListener('mouseup', onPointerUp);
      window.addEventListener('touchend', onPointerUp);

      const saved = parseFloat(localStorage.getItem('emas_split_ratio') || '0.52');
      window.setSplitRatio(saved);
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', setup);
    } else {
      setTimeout(setup, 50);
    }
  })();

  async function vehicleRequest(path, method = 'GET', body = null) {
    return vehicleClient.request(method, path, body ? JSON.stringify(body) : '');
  }

  function telemetryFresh() {
    const age = telemetryNow() - state.lastTelemetryAt;
    return state.telemetryAvailable && age >= 0 && age <= 5000;
  }

  function controlReady() {
    if (simulationMode || !telemetryFresh()) {
      toast('Kawalan tidak tersedia: bacaan kenderaan belum segar.');
      return false;
    }
    return true;
  }

  function updateUI() {
    const isFresh = telemetryFresh();
    const indicator = $('live-indicator');
    const statusText = $('status-pill-text');
    if (indicator) indicator.dataset.state = state.isSimulated ? 'simulation' : (isFresh ? 'live' : (state.isLive ? 'stale' : 'waiting'));
    if (statusText) {
      statusText.textContent = state.isSimulated ? 'SIMULASI LAPTOP · DATA CONTOH'
        : (isFresh ? 'IHU · DATA LANGSUNG' : (state.isLive ? 'SAMBUNGAN TERPUTUS · DATA TERAKHIR' : 'MENUNGGU DATA KENDERAAN'));
    }

    const socVal = hasNumber(state.soc) ? Number(state.soc) : null;
    if ($('soc-display')) $('soc-display').textContent = socVal === null ? '—' : Math.round(socVal);
    if ($('range-display')) $('range-display').textContent = hasNumber(state.rangeKm) ? `${Math.round(Number(state.rangeKm))} km` : '— km';
    if ($('nav-range-status')) $('nav-range-status').textContent = hasNumber(state.rangeKm) ? `${Math.round(Number(state.rangeKm))} km` : '— km';

    // Battery Pack Data Grid
    const v = hasNumber(state.voltage) ? Number(state.voltage).toFixed(1) : '—';
    const a = hasNumber(state.current) ? Number(state.current).toFixed(1) : '—';
    if ($('pack-electrical')) $('pack-electrical').textContent = `${v} V · ${a} A`;
    if ($('pack-voltage')) $('pack-voltage').textContent = `${v} V`;
    if ($('pack-current')) $('pack-current').textContent = `${a} A`;
    if ($('pack-temp')) $('pack-temp').textContent = hasNumber(state.battTemp) ? `${Number(state.battTemp).toFixed(1)}°C` : '—°C';
    if ($('pack-kwh')) $('pack-kwh').textContent = hasNumber(state.remainingKwh) ? `${Number(state.remainingKwh).toFixed(1)} kWh` : '— kWh';

    const kwVal = hasNumber(state.powerKw) ? Number(state.powerKw) : null;
    if (kwVal === null) {
      if ($('power-display')) $('power-display').textContent = '—';
      if ($('power-mode-label')) $('power-mode-label').textContent = 'KUASA SEMASA';
    }
    const isRegen = kwVal !== null && kwVal < -0.1;
    if ($('power-display')) {
      if (kwVal !== null) $('power-display').textContent = isRegen ? kwVal.toFixed(1) : `+${kwVal.toFixed(1)}`;
      $('power-display').className = `text-3xl font-extrabold font-mono ${isRegen ? 'text-emerald-400' : 'text-cyan-300'}`;
    }
    if ($('power-mode-label')) {
      if (kwVal !== null) $('power-mode-label').textContent = isRegen ? 'KUASA REGEN (CAS)' : 'KUASA TRAKSI';
      $('power-mode-label').className = `text-[11px] font-bold uppercase tracking-wider block ${isRegen ? 'text-emerald-400' : 'text-cyan-400'}`;
    }

    // Dynamic Power Graph Live Displays
    if ($('power-graph-live-traction')) {
      $('power-graph-live-traction').textContent = kwVal === null ? '+0.0 kW' : (isRegen ? '+0.0 kW' : `+${kwVal.toFixed(1)} kW`);
    }
    if ($('power-graph-live-regen')) {
      $('power-graph-live-regen').textContent = kwVal === null ? '−0.0 kW' : (isRegen ? `${kwVal.toFixed(1)} kW` : '−0.0 kW');
    }
    if (kwVal !== null) {
      if (kwVal > state.peakTractionKw) {
        state.peakTractionKw = kwVal;
        if ($('power-peak-traction')) $('power-peak-traction').textContent = `+${kwVal.toFixed(1)} kW`;
      }
      if (kwVal < state.peakRegenKw) {
        state.peakRegenKw = kwVal;
        if ($('power-peak-regen')) $('power-peak-regen').textContent = `${kwVal.toFixed(1)} kW`;
      }

      // Dynamic Recuperated % calculation
      if (kwVal > 0.1) {
        state.accumTractionKw = (state.accumTractionKw || 0) + kwVal;
      } else if (kwVal < -0.1) {
        state.accumRegenKw = (state.accumRegenKw || 0) + Math.abs(kwVal);
      }
      const totalPwr = (state.accumTractionKw || 0) + (state.accumRegenKw || 0);
      if (totalPwr > 1.0 && $('power-regen-pct')) {
        const pct = ((state.accumRegenKw / totalPwr) * 100).toFixed(1);
        $('power-regen-pct').textContent = `${pct}%`;
      }
    }

    // Dynamic Range & Battery Analytics
    const officialRange = hasNumber(state.rangeKm) ? Math.round(Number(state.rangeKm)) : null;
    const realKwh = hasNumber(state.remainingKwh) ? Number(state.remainingKwh) : (socVal !== null ? (49.52 * (socVal / 100)) : null);
    
    // Real-world dynamic range (realistic ~16.2 kWh/100km mixed/highway consumption)
    // 49.52 kWh / 16.2 = ~305 km full battery. At 31% => ~95 km.
    const realDynamicRange = realKwh !== null ? Math.round((realKwh / 16.2) * 100) : (socVal !== null ? Math.round(305 * (socVal / 100)) : null);

    if ($('power-real-range')) {
      $('power-real-range').textContent = realDynamicRange !== null ? `~${realDynamicRange} km` : '— km';
    }
    if ($('power-meter-range')) {
      $('power-meter-range').textContent = officialRange !== null ? `${officialRange} km` : '— km';
    }
    if ($('power-real-kwh')) {
      $('power-real-kwh').textContent = realKwh !== null ? `${realKwh.toFixed(1)} kWh` : '— kWh';
    }
    if ($('power-range-diff') && officialRange !== null && realDynamicRange !== null) {
      const diff = realDynamicRange - officialRange;
      $('power-range-diff').textContent = diff <= 0 ? `Sisihan: ${diff} km` : `Sisihan: +${diff} km`;
    }

    // Dynamic Energy Breakdown Strip
    const hvacActive = !state.fanOff;
    const curSpeed = hasNumber(state.speed) ? Number(state.speed) : 0;
    let motorPct = 75;
    let hvacPct = hvacActive ? 20 : 5;
    let cabinPct = 5;

    if (kwVal !== null && kwVal > 1.0) {
      const estHvacKw = hvacActive ? 1.5 : 0.2;
      const estCabinKw = 0.3;
      const totalKw = Math.max(1.0, kwVal + estHvacKw + estCabinKw);
      motorPct = Math.round((kwVal / totalKw) * 100);
      hvacPct = Math.round((estHvacKw / totalKw) * 100);
      cabinPct = Math.max(2, 100 - motorPct - hvacPct);
    } else if (curSpeed < 1.0) {
      motorPct = 0;
      hvacPct = hvacActive ? 80 : 20;
      cabinPct = hvacActive ? 20 : 80;
    }

    if ($('bar-motor')) $('bar-motor').style.width = `${motorPct}%`;
    if ($('bar-hvac')) $('bar-hvac').style.width = `${hvacPct}%`;
    if ($('bar-cabin')) $('bar-cabin').style.width = `${cabinPct}%`;
    if ($('power-breakdown-text')) {
      $('power-breakdown-text').textContent = `Motor ${motorPct}% · Aircond ${hvacPct}% · Kabin ${cabinPct}%`;
    }

    // SOH & Battery Health Hub Displays
    if ($('soh-main-pct')) $('soh-main-pct').textContent = hasNumber(state.soh) ? `${Number(state.soh).toFixed(1)}%` : '99.8%';
    if ($('soh-delta-val')) $('soh-delta-val').textContent = `${state.cellDeltaMv || 11} mV`;
    if ($('soh-vmax-text')) $('soh-vmax-text').textContent = `${Number(state.cellVMax || 3.292).toFixed(3)} V`;
    if ($('soh-vmin-text')) $('soh-vmin-text').textContent = `${Number(state.cellVMin || 3.281).toFixed(3)} V`;
    if ($('soh-vavg-text')) $('soh-vavg-text').textContent = `${Number(state.cellVAvg || 3.287).toFixed(3)} V`;
    if ($('matrix-count-healthy')) $('matrix-count-healthy').textContent = state.cellsHealthy || 120;
    if ($('matrix-count-dead')) $('matrix-count-dead').textContent = state.cellsDead || 0;

    // Ribbon
    if ($('ribbon-regen') && $('ribbon-traction')) {
      if (isRegen) {
        const pct = kwVal === null ? 0 : Math.min(100, Math.abs(kwVal) / 50 * 100);
        $('ribbon-regen').style.width = pct + '%';
        $('ribbon-traction').style.width = '0%';
      } else {
        const pct = kwVal === null ? 0 : Math.min(100, Math.abs(kwVal) / 100 * 100);
        $('ribbon-traction').style.width = pct + '%';
        $('ribbon-regen').style.width = '0%';
      }
    }

    // Speed & Ambient
    if ($('speed-display')) $('speed-display').textContent = hasNumber(state.speed) ? `${Math.round(Number(state.speed))} km/j` : '— km/j';
    if ($('ambient-temp')) $('ambient-temp').textContent = hasNumber(state.ambientTemp) ? `${Number(state.ambientTemp).toFixed(1)}°C` : '—°C';

    // Gears
    const activeGear = typeof state.gear === 'string' ? state.gear.toUpperCase() : '';
    ['P', 'R', 'N', 'D'].forEach(g => {
      const el = $('gear-' + g);
      if (el) {
        if (g === activeGear) {
          el.className = 'px-3 py-0.5 rounded-lg bg-amber-400 text-black shadow-md font-extrabold';
        } else {
          el.className = 'px-3 py-0.5 rounded-lg text-white/40';
        }
      }
    });

    // Trip
    const raw = hasNumber(state.tripKm) ? Number(state.tripKm) : null;
    const offset = parseFloat(localStorage.getItem('customTripOffsetKm') || '0');
    const dispTrip = raw === null ? '—' : Math.max(0, raw - offset).toFixed(1);
    if ($('trip-readout')) $('trip-readout').textContent = `${dispTrip} km`;

    // Climate
    if ($('temp-val')) $('temp-val').textContent = hasNumber(state.setpoint) ? Number(state.setpoint).toFixed(1) : '—';
    if ($('fan-val')) {
      $('fan-val').textContent = state.fanOff ? 'MATI (OFF)' : (hasNumber(state.fan) ? `Tahap ${Number(state.fan)} / 8` : '— / 8');
    }

    const pwrBtn = $('btn-climate-power');
    const pwrTxt = $('climate-power-text');
    if (pwrBtn && pwrTxt) {
      if (state.fanOff) {
        pwrBtn.className = 'px-2.5 py-1 rounded-xl text-xs font-bold bg-rose-500/20 border border-rose-500/40 text-rose-300 hover:bg-rose-500/30 active:scale-95 transition-all flex items-center gap-1.5 cursor-pointer shadow-sm power-off';
        pwrTxt.textContent = 'A/C OFF';
      } else {
        pwrBtn.className = 'px-2.5 py-1 rounded-xl text-xs font-bold bg-emerald-500/20 border border-emerald-500/40 text-emerald-300 hover:bg-emerald-500/30 active:scale-95 transition-all flex items-center gap-1.5 cursor-pointer shadow-sm power-on';
        pwrTxt.textContent = 'A/C ON';
      }
    }

    document.querySelectorAll('.fan-dot').forEach(btn => {
      const f = parseInt(btn.getAttribute('data-f'));
      if (!state.fanOff && hasNumber(state.fan) && f <= Number(state.fan)) {
        btn.className = 'fan-dot h-10 rounded-xl bg-emerald-400 text-black font-extrabold text-sm active:scale-95 transition-all';
      } else {
        btn.className = 'fan-dot h-10 rounded-xl bg-[#14202b] text-white/50 font-extrabold text-sm border border-white/10 active:scale-95 transition-all';
      }
    });

    const modePill = $('climate-mode-pill');
    if (modePill) {
      if (state.autoClimateEnabled === null) {
        modePill.textContent = 'TIADA DATA HVAC';
        modePill.className = 'px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-white/10 border border-white/20 text-white/70';
      } else if (!state.autoClimateEnabled || state.autoClimateMode === 'off') {
        modePill.textContent = 'MANUAL CONTROL';
        modePill.className = 'px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-white/10 border border-white/20 text-white/70';
      } else {
        modePill.textContent = `${state.autoClimateMode.toUpperCase()} AUTO`;
        modePill.className = 'px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-emerald-500/15 border border-emerald-500/30 text-emerald-400';
      }
    }

    ['adaptive', 'quick_cool', 'comfort', 'off'].forEach(m => {
      const btn = $('cm-' + m);
      if (btn) {
        const isSel = state.autoClimateEnabled !== null && ((m === 'off' && !state.autoClimateEnabled) || (state.autoClimateEnabled && state.autoClimateMode === m));
        if (isSel) {
          btn.className = 'p-2 rounded-2xl bg-emerald-500/15 border border-emerald-500/40 text-left transition-all';
          btn.querySelector('span:first-child').className = 'text-xs block font-bold text-emerald-300';
        } else {
          btn.className = 'p-2 rounded-2xl bg-[#0c141c] border border-white/10 hover:border-white/20 text-left transition-all';
          btn.querySelector('span:first-child').className = 'text-xs block font-bold text-white';
        }
      }
    });
  }

  let isPolling = false;
  async function pollTelemetry() {
    if (simulationMode || isPolling) return;
    isPolling = true;
    try {
      const res = await vehicleRequest('/api/energy');
      const data = res.ok === true ? res : null;
      state.telemetryAvailable = false;

      if (data) {
        const batt = data.battery || {};
        const trip = data.trip || {};
        const hvac = data.hvac || {};
        const smart = data.smart_climate || {};

        const hasValidData = [batt.soc_pct, data.soc_pct, data.soc, batt.voltage_v,
          data.voltage, data.speed_kmh, data.speed].some(hasNumber);

        if (hasValidData) {
          state.isLive = true;
          state.isSimulated = false;
          state.lastTelemetryAt = data._observedAt;
          state.telemetryAvailable = data._observedAt !== null;

          // Battery SoC
          if (batt.soc_pct !== undefined) state.soc = batt.soc_pct;
          else if (data.soc_pct !== undefined) state.soc = data.soc_pct;
          else if (data.soc !== undefined) state.soc = data.soc;

          // Voltage & Current
          if (batt.voltage_v !== undefined) state.voltage = batt.voltage_v;
          else if (data.voltage !== undefined) state.voltage = data.voltage;

          if (batt.current_a !== undefined) state.current = batt.current_a;
          else if (data.current !== undefined) state.current = data.current;

          // Power kW
          if (batt.power_kw !== undefined) state.powerKw = batt.power_kw;
          else if (data.motor_power_kw !== undefined) state.powerKw = data.motor_power_kw;
          else if (data.powerKw !== undefined) state.powerKw = data.powerKw;
          else if (data.power_kw !== undefined) state.powerKw = data.power_kw;
          else if (hasNumber(state.voltage) && hasNumber(state.current)) {
            state.powerKw = (Number(state.voltage) * Number(state.current)) / 1000.0;
          }

          // Range & Remaining kWh
          if (batt.range_km !== undefined) state.rangeKm = batt.range_km;
          else if (data.range_km !== undefined) state.rangeKm = data.range_km;
          else if (data.rangeKm !== undefined) state.rangeKm = data.rangeKm;

          if (data.remainingKwh !== undefined) state.remainingKwh = data.remainingKwh;
          else if (data.remaining_kwh !== undefined) state.remainingKwh = data.remaining_kwh;

          // Speed & Gear
          if (data.speed_kmh !== undefined) state.speed = data.speed_kmh;
          else if (data.speed !== undefined) state.speed = data.speed;

          if (data.gear !== undefined) state.gear = data.gear;

          // Temperatures
          if (data.ambient_temp_c !== undefined) state.ambientTemp = data.ambient_temp_c;
          else if (data.outsideTemp !== undefined) state.ambientTemp = data.outsideTemp;
          else if (data.ambientTemp !== undefined) state.ambientTemp = data.ambientTemp;

          if (data.battTemp !== undefined) state.battTemp = data.battTemp;

          // Trip
          if (trip.distance_km !== undefined) state.tripKm = trip.distance_km;
          else if (trip.distanceKm !== undefined) state.tripKm = trip.distanceKm;

          // HVAC
          if (hvac.setpoint !== undefined) state.setpoint = hvac.setpoint;
          else if (hvac.tempDrv !== undefined) state.setpoint = hvac.tempDrv;

          if (hvac.fan !== undefined) state.fan = hvac.fan;
          if (hvac.powerOn !== undefined) state.fanOff = !hvac.powerOn;
          else if (hvac.power !== undefined) state.fanOff = hvac.power === 0;

          if (hvac.auto_enabled !== undefined) state.autoClimateEnabled = !!hvac.auto_enabled;
          else if (smart.enabled !== undefined) state.autoClimateEnabled = !!smart.enabled;

          if (hvac.auto_mode !== undefined) state.autoClimateMode = hvac.auto_mode;
          else if (smart.mode !== undefined) state.autoClimateMode = smart.mode;

          // Aegis SOH & Cell Balancer Data from Daemon
          if (batt.soh_pct !== undefined) state.soh = batt.soh_pct;
          if (batt.cell_delta_mv !== undefined) state.cellDeltaMv = batt.cell_delta_mv;
          if (batt.cell_v_avg !== undefined) state.cellVAvg = batt.cell_v_avg;
          if (batt.cell_v_max !== undefined) state.cellVMax = batt.cell_v_max;
          if (batt.cell_v_min !== undefined) state.cellVMin = batt.cell_v_min;
          if (batt.cells_healthy !== undefined) state.cellsHealthy = batt.cells_healthy;
          if (batt.cells_dead !== undefined) state.cellsDead = batt.cells_dead;

          // Push to graph
          graphHistory.shift();
          graphHistory.push(hasNumber(state.powerKw) ? Number(state.powerKw) : null);
          if (state.activeSplitTab === 'power') {
            drawGraph();
          } else if (state.activeSplitTab === 'cells') {
            renderCellsGrid();
          }
        }
      }
      updateUI();
    } catch (_) {
      state.telemetryAvailable = false;
      updateUI();
    } finally {
      isPolling = false;
    }
  }

  // Public Actions
  window.controlWindow = async function(action) {
    if (!controlReady() || !['crack', 'close'].includes(action)) return;
    toast(action === 'crack' ? 'Menghantar arahan celahan tingkap…' : 'Menghantar arahan rapat tingkap…');
    const res = await vehicleRequest(`/api/window?action=${encodeURIComponent(action)}`);
    if (res && res.ok === true) {
      toast('Arahan tingkap diterima. Kedudukan sebenar belum disahkan.');
    } else {
      toast(`Gagal kawal tingkap (${res && res.error ? res.error : 'Ralat'}) ⚠️`);
    }
  };

  window.setSmartClimate = async function(mode, enabled) {
    if (!controlReady()) return;
    toast(`Menghantar Smart Climate: ${mode}…`);
    const res = await vehicleRequest(`/api/climate/auto?mode=${encodeURIComponent(mode)}&enabled=${enabled}`);
    if (res && res.ok === true) {
      state.autoClimateEnabled = enabled;
      state.autoClimateMode = mode;
      updateUI();
      toast(enabled ? `Smart Climate: ${mode.toUpperCase()} diaktifkan! ❄️` : 'Smart Climate dimatikan (Kawalan Manual).');
    } else {
      toast(`Gagal menukar Smart Climate (${res && res.error ? res.error : 'Ralat'}) ⚠️`);
    }
  };

  window.adjustTemp = async function(delta) {
    if (!controlReady() || !Number.isFinite(delta)) return;
    const cur = state.setpoint !== null ? state.setpoint : 22.0;
    const target = Math.max(17.0, Math.min(32.0, Math.round((cur + delta) * 2) / 2));
    toast(`Menghantar suhu sasaran: ${target.toFixed(1)}°C…`);
    const res = await vehicleRequest(`/api/climate/temp?target=${target.toFixed(1)}`);
    if (res && res.ok === true) {
      state.setpoint = target;
      updateUI();
      toast(`Arahan suhu ${target.toFixed(1)}°C diterima. Bacaan sebenar akan dikemas kini.`);
    } else {
      toast(`Gagal menukar suhu (${res && res.error ? res.error : 'Ralat'}) ⚠️`);
    }
  };

  window.setFanSpeed = async function(speed) {
    if (!controlReady() || !Number.isInteger(speed)) return;
    const lvl = Math.max(1, Math.min(8, parseInt(speed, 10)));
    toast(`Menghantar kelajuan kipas: Tahap ${lvl}…`);
    const res = await vehicleRequest(`/api/climate/fan?level=${lvl}`);
    if (res && res.ok === true) {
      state.fan = lvl;
      state.fanOff = false;
      updateUI();
      toast(`Arahan kipas tahap ${lvl} diterima. Bacaan sebenar akan dikemas kini.`);
    } else {
      toast(`Gagal menukar kipas (${res && res.error ? res.error : 'Ralat'}) ⚠️`);
    }
  };

  window.resetCustomTrip = function() {
    if (!hasNumber(state.tripKm)) {
      toast('Trip belum tersedia daripada kenderaan.');
      return;
    }
    const raw = Number(state.tripKm);
    localStorage.setItem('customTripOffsetKm', raw);
    toast('Trip perjalanan telah di-reset ke 0.0 km! 🔄');
    updateUI();
  };

  window.toggleClimatePower = async function() {
    if (!controlReady()) return;
    const isCurrentlyOn = !state.fanOff;
    const targetState = isCurrentlyOn ? 0 : 1;
    toast(isCurrentlyOn ? 'Mematikan Aircond…' : 'Menghidupkan Aircond…');
    const res = await vehicleRequest(`/api/climate/power?state=${targetState}`);
    if (res && res.ok === true) {
      state.fanOff = (targetState === 0);
      updateUI();
      toast(targetState === 0 ? '✓ Aircond dimatikan (OFF)' : '✓ Aircond dihidupkan (ON)');
    } else {
      toast(`Gagal menukar kuasa A/C ⚠️`);
    }
  };

  // Init
  window.addEventListener('DOMContentLoaded', () => {
    updateUI();
    drawGraph();
    renderCellsGrid();
    window.addEventListener('resize', drawGraph);

    const initialTab = new URLSearchParams(window.location.search).get('tab');
    if (initialTab && typeof window.switchSplitTab === 'function') {
      window.switchSplitTab(initialTab);
    }

    pollTelemetry();
    setInterval(pollTelemetry, 1500);

    setInterval(() => {
      const clock = $('clock');
      if (clock) clock.textContent = new Date().toTimeString().substring(0, 5);
    }, 1000);
  });

})();
