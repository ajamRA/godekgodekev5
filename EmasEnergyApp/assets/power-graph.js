// Motor Dynamics Graph: Real-time Live Vehicle CAN Power & Recuperation
// Supports Smart Dynamic Auto-Scaling (Skala Dinamik Auto) for gentle city cruising up to 85 kW full throttle

const motorLive = {
  actual: null,
  history: [],
  maxHistorySec: 20,
  source: 'MENUNGGU DATA',
  // Dynamic scale states with smooth transition (calibrated to 60 kW instrument meter)
  scalePos: 30.0,  // Positive scale: auto-ranges from 30 kW to 60 kW
  scaleNeg: 20.0,  // Negative scale: auto-ranges from 20 kW to 35 kW
  peakPos: 0.0,
  peakNeg: 0.0
};

window.updateLiveMotorPower = function(kw, liveSource = true, powerKind = 'Kuasa elektrik') {
  if (typeof kw !== 'number' || !Number.isFinite(kw)) {
    motorLive.actual = null;
    motorLive.history = [];
    motorLive.source = 'MENUNGGU DATA';
    drawMotorGraph();
    return;
  }
  const v = kw;
  motorLive.actual = v;
  motorLive.source = liveSource ? powerKind : 'SIMULASI';
  
  if (v > motorLive.peakPos) motorLive.peakPos = v;
  if (v < 0 && Math.abs(v) > motorLive.peakNeg) motorLive.peakNeg = Math.abs(v);

  const now = performance.now() / 1000;
  motorLive.history.push({ t: now, v: v });

  // Retain past 20 seconds of data points
  while (motorLive.history.length > 0 && motorLive.history[0].t < now - motorLive.maxHistorySec) {
    motorLive.history.shift();
  }
  drawMotorGraph();
};

function powerGraphMarkup() {
  return `
    <section class="card chart-card live-power-card">
      <div class="card-head">
        <div style="display:flex; align-items:center; gap:8px;">
          <h2>Kuasa elektrik langsung</h2>
          <span class="badge" id="motor-scale-badge" style="font-size:11px; padding:2px 8px; border-radius:12px; background:rgba(255,255,255,0.08); color:var(--muted);">Skala Auto: +30 / −20 kW</span>
        </div>
        <span class="badge" id="motor-badge">MENUNGGU DATA</span>
      </div>
      <div class="motor-reading">
        <strong id="motor-kw">— <small>kW</small></strong>
        <span id="motor-direction">Menunggu data kereta</span>
      </div>
      <svg class="motor-chart" viewBox="0 0 960 200" preserveAspectRatio="none" style="width:100%; height:200px; display:block; margin:8px 0 2px 0;" role="img" aria-label="Graf penggunaan motor dan kuasa regen braking auto-adaptif 20 saat">
        <defs>
          <linearGradient id="motor-grad-throttle" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#e08a30" stop-opacity="0.50"/>
            <stop offset="100%" stop-color="#e08a30" stop-opacity="0.04"/>
          </linearGradient>
          <linearGradient id="motor-grad-regen" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#3db66e" stop-opacity="0.04"/>
            <stop offset="100%" stop-color="#3db66e" stop-opacity="0.50"/>
          </linearGradient>
          <clipPath id="motor-clip-positive">
            <rect x="70" y="10" width="875" height="115"/>
          </clipPath>
          <clipPath id="motor-clip-negative">
            <rect x="70" y="125" width="875" height="65"/>
          </clipPath>
        </defs>

        <!-- Horizontal Guide Gridlines -->
        <path d="M70 20H945" stroke="var(--line)" stroke-dasharray="4 6" opacity="0.6" stroke-width="1.2"/>
        <path d="M70 72.5H945" stroke="var(--line)" stroke-dasharray="4 6" opacity="0.6" stroke-width="1.2"/>
        <path d="M70 125H945" stroke="var(--muted)" opacity="0.8" stroke-width="1.8"/>
        <path d="M70 175H945" stroke="var(--line)" stroke-dasharray="4 6" opacity="0.6" stroke-width="1.2"/>

        <!-- Y-Axis Labels (Dynamic Values) -->
        <g class="graph-labels" font-size="12" font-weight="600" fill="var(--muted)" text-anchor="end">
          <text id="graph-lbl-max-pos" x="60" y="24">+30 kW</text>
          <text id="graph-lbl-mid-pos" x="60" y="76">+15 kW</text>
          <text x="60" y="129">0 kW</text>
          <text id="graph-lbl-max-neg" x="60" y="179">−20 kW</text>
        </g>

        <!-- X-Axis Time Labels -->
        <g class="graph-labels" font-size="11" font-weight="600" fill="var(--muted)" opacity="0.8">
          <text x="70" y="196">−20s</text>
          <text x="288" y="196">−15s</text>
          <text x="507" y="196">−10s</text>
          <text x="726" y="196">−5s</text>
          <text x="915" y="196">Live</text>
        </g>

        <!-- Throttle / Traction Fill and Line -->
        <path id="motor-fill-up" clip-path="url(#motor-clip-positive)" fill="url(#motor-grad-throttle)"/>
        <path id="motor-line-up" clip-path="url(#motor-clip-positive)" stroke="#e08a30" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" fill="none"/>

        <!-- Regen Braking Fill and Line -->
        <path id="motor-fill-down" clip-path="url(#motor-clip-negative)" fill="url(#motor-grad-regen)"/>
        <path id="motor-line-down" clip-path="url(#motor-clip-negative)" stroke="#3db66e" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" fill="none"/>

        <!-- Live Cursor Point -->
        <circle id="motor-dot-glow" cx="945" cy="125" r="9" fill="var(--muted)" opacity="0.25"/>
        <circle id="motor-dot" cx="945" cy="125" r="5" fill="var(--muted)"/>
      </svg>
    </section>`;
}

function drawMotorGraph() {
  const output = document.getElementById('motor-kw');
  if (!output) return;

  const badge = document.getElementById('motor-badge');
  if (badge) badge.textContent = motorLive.source;

  const v = motorLive.actual;
  if (v === null) {
    output.innerHTML = `— <small>kW</small>`;
    output.style.color = 'var(--muted)';
    const dirEl = document.getElementById('motor-direction');
    if (dirEl) {
      dirEl.textContent = 'Menunggu isyarat CAN bus kenderaan...';
      dirEl.style.color = 'var(--muted)';
    }
    for (const id of ['motor-line-up', 'motor-line-down', 'motor-fill-up', 'motor-fill-down']) {
      const el = document.getElementById(id);
      if (el) el.setAttribute('d', '');
    }
    const dot = document.getElementById('motor-dot');
    const glow = document.getElementById('motor-dot-glow');
    if (dot) { dot.setAttribute('cy', '125'); dot.setAttribute('fill', 'var(--muted)'); }
    if (glow) { glow.setAttribute('cy', '125'); glow.setAttribute('fill', 'var(--muted)'); }
    return;
  }

  // Determine current window peaks for smart auto-scaling
  let windowMaxPos = 0;
  let windowMaxNeg = 0;
  for (let i = 0; i < motorLive.history.length; i++) {
    const val = motorLive.history[i].v;
    if (val > windowMaxPos) windowMaxPos = val;
    if (val < 0 && Math.abs(val) > windowMaxNeg) windowMaxNeg = Math.abs(val);
  }
  if (v > windowMaxPos) windowMaxPos = v;
  if (v < 0 && Math.abs(v) > windowMaxNeg) windowMaxNeg = Math.abs(v);

  // Compute adaptive target scale matching e.MAS 5 cluster gauge (max 60 kW)
  // Positive (Throttle): 30 kW cruising -> 45 kW brisk -> 60 kW full throttle
  let targetScalePos = 30.0;
  if (windowMaxPos > 45) {
    targetScalePos = 60.0;
  } else if (windowMaxPos > 24) {
    targetScalePos = 45.0;
  } else {
    targetScalePos = 30.0;
  }

  // Negative (Regen braking): 20 kW cruising -> 35 kW hard braking
  let targetScaleNeg = 20.0;
  if (windowMaxNeg > 18) {
    targetScaleNeg = 35.0;
  } else {
    targetScaleNeg = 20.0;
  }

  // Smooth easing towards target
  if (targetScalePos > motorLive.scalePos) {
    // Instant expand on sudden acceleration so curve never clips
    motorLive.scalePos = targetScalePos;
  } else {
    motorLive.scalePos += (targetScalePos - motorLive.scalePos) * 0.04;
  }

  if (targetScaleNeg > motorLive.scaleNeg) {
    // Instant expand on hard braking
    motorLive.scaleNeg = targetScaleNeg;
  } else {
    motorLive.scaleNeg += (targetScaleNeg - motorLive.scaleNeg) * 0.04;
  }

  const curScalePos = Math.min(60.0, Math.max(25.0, motorLive.scalePos));
  const curScaleNeg = Math.min(35.0, Math.max(16.0, motorLive.scaleNeg));

  // Update Y-Axis Dynamic Text Labels
  const lblMaxPos = document.getElementById('graph-lbl-max-pos');
  if (lblMaxPos) lblMaxPos.textContent = `+${Math.round(curScalePos)} kW`;
  const lblMidPos = document.getElementById('graph-lbl-mid-pos');
  if (lblMidPos) lblMidPos.textContent = `+${Math.round(curScalePos / 2)} kW`;
  const lblMaxNeg = document.getElementById('graph-lbl-max-neg');
  if (lblMaxNeg) lblMaxNeg.textContent = `−${Math.round(curScaleNeg)} kW`;

  const scaleBadge = document.getElementById('motor-scale-badge');
  if (scaleBadge) {
    scaleBadge.textContent = `Skala Auto: +${Math.round(curScalePos)} / −${Math.round(curScaleNeg)} kW`;
  }

  // Status & Reading Header
  if (v > 0.1) {
    output.innerHTML = `+${v.toFixed(1)} <small>kW</small>`;
    output.style.color = '#e08a30';
  } else if (v < -0.1) {
    output.innerHTML = `${v.toFixed(1)} <small>kW</small>`;
    output.style.color = '#3db66e';
  } else {
    output.innerHTML = `0.0 <small>kW</small>`;
    output.style.color = 'var(--ink)';
  }

  const dirEl = document.getElementById('motor-direction');
  if (dirEl) {
    if (v > 0.1) {
      dirEl.textContent = `Kuasa keluar · +${v.toFixed(1)} kW`;
      dirEl.style.color = '#e08a30';
    } else if (v < -0.1) {
      dirEl.textContent = `Kuasa masuk · ${v.toFixed(1)} kW (cas / rekuperasi)`;
      dirEl.style.color = '#3db66e';
    } else {
      dirEl.textContent = 'Kuasa bersih hampir sifar';
      dirEl.style.color = 'var(--muted)';
    }
  }

  // Calculate coordinates
  // X: 70 -> 945 (span 875) over 20 seconds
  // Y baseline 0 kW = 125
  // Positive: 125 down to 20 (span 105 px)
  // Negative: 125 up to 175 (span 50 px)
  const now = performance.now() / 1000;
  const pts = motorLive.history.map(p => {
    const age = Math.max(0, Math.min(20, now - p.t));
    const x = 945 - (age / 20) * 875;
    let y = 125;
    if (p.v > 0) {
      const ratio = Math.min(1.05, p.v / curScalePos);
      y = 125 - ratio * 105;
    } else if (p.v < 0) {
      const ratio = Math.min(1.05, Math.abs(p.v) / curScaleNeg);
      y = 125 + ratio * 50;
    }
    return [Math.max(70, Math.min(945, x)), y];
  });

  let liveY = 125;
  if (v > 0) {
    const ratio = Math.min(1.05, v / curScalePos);
    liveY = 125 - ratio * 105;
  } else if (v < 0) {
    const ratio = Math.min(1.05, Math.abs(v) / curScaleNeg);
    liveY = 125 + ratio * 50;
  }
  pts.push([945, liveY]);

  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ');
  const area = `${line} L945 125 L${pts[0][0].toFixed(1)} 125 Z`;

  for (const id of ['motor-line-up', 'motor-line-down']) {
    const el = document.getElementById(id);
    if (el) el.setAttribute('d', line);
  }
  for (const id of ['motor-fill-up', 'motor-fill-down']) {
    const el = document.getElementById(id);
    if (el) el.setAttribute('d', area);
  }

  const dot = document.getElementById('motor-dot');
  const glow = document.getElementById('motor-dot-glow');
  const dotColor = v > 0.1 ? '#e08a30' : (v < -0.1 ? '#3db66e' : 'var(--muted)');

  if (dot) {
    dot.setAttribute('cy', liveY.toFixed(1));
    dot.setAttribute('fill', dotColor);
  }
  if (glow) {
    glow.setAttribute('cy', liveY.toFixed(1));
    glow.setAttribute('fill', dotColor);
  }
}

// 60fps Redraw loop for butter-smooth time scrolling
function motorTick() {
  if (document.getElementById('motor-kw')) {
    drawMotorGraph();
  }
  requestAnimationFrame(motorTick);
}
requestAnimationFrame(motorTick);
