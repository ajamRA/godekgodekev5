# EmasEnergy improvement plan

Scope: improve the installed `com.noor.thenoorcar` UI using fields already supported by the local telemetry contract. No new daemon or vehicle write path.

## Confirmed contract

Rendered/used today:
- battery SOC, voltage, current, range, temperature when supplied
- speed, gear, motor power
- trip summary
- HVAC controls and some HVAC state
- doors, TPMS warning state, seatbelt state
- frunk/trunk controls
- ventilation/close
- AVAS controls

Not confirmed:
- real tyre pressure. `tpms[key].p` is optional; current evidence only proves warning flags. Never show bar/psi unless a valid numeric pressure field is present.

## Priority changes

### P1: make existing data more useful

1. Add a compact `Vehicle status` strip on Overview:
   - gear
   - speed
   - seatbelt status
   - door warning count
   - TPMS: `Normal`, `Amaran`, or `Tiada data`
   - source badge: `DATA IHU`, `BACAAN TERAKHIR`, `TIADA DATA`

2. Add `Load now` to the power page only when numeric:
   - traction/motor power
   - HVAC power
   - 12 V accessory power
   - hide absent values, do not substitute zero

3. Add outside temperature beside HVAC setpoint only when numeric.

4. Render seatbelt state instead of parsing it silently.

### P2: remove misleading UI

1. Remove static/demo graph labels that imply live data when no live sample exists.
2. Keep tyre panel warning-only. Do not add pressure numbers.
3. Keep AVAS controls behind the existing explicit control flow; do not broaden scope.
4. Keep drive mode read-only until `/api/drive_mode` returns a verified live mode field.

### P3: defer

- Mirror, ambient light, trunk/hood advanced controls: require verified `ecarx.settings`/VHAL adapter.
- Phone remote control: separate project; no direct system permission assumption.
- New daemon: unnecessary while existing telemetry endpoint is sufficient.

## Acceptance checks

- 390 px viewport: no horizontal overflow or overlap.
- Missing optional fields render `—`/`Tiada data`, never fabricated values.
- TPMS shows no pressure unless all displayed values are numeric and source-backed.
- Existing HVAC, frunk, trunk, AVAS, telemetry polling behavior remains unchanged.
- Run `node --test tests/ui-regression.test.cjs`.
- Run `npm run lint` and `npm run build` only in the `D:/apps/emas5` project after any phone PWA changes.
- Rebuild/install IHU APK only after source tests, APK verification, and user approval.
