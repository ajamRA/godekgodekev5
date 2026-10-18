(function (root,factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.CompanionTelemetry = api;
}(typeof window !== 'undefined' ? window : globalThis,function () {
  'use strict';
  const number = (v,min = -Infinity,max = Infinity) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max ? v : null;
  function normalize(data, now = Date.now()) {
    if (!data || typeof data !== 'object' || Array.isArray(data) || data.ok === false) throw new Error('INVALID_TELEMETRY');
    // Producer-provided sample age is authoritative. Wall clocks on phone/IHU can differ.
    if (Object.prototype.hasOwnProperty.call(data,'age_ms') && (number(data.age_ms,0) === null || data.age_ms > 15000)) throw new Error('STALE_TELEMETRY');
    const b = data.battery && typeof data.battery === 'object' ? data.battery : {};
    const battery = {source:typeof data.source === 'string' ? data.source : 'daemon',
      soc:number(b.soc_pct,0,100),voltage:number(b.voltage_v,100,1000),current:number(b.current_a,-500,500),observedAt:now};
    const known = {}, doors = {};
    for (const key of ['fl','fr','rl','rr','trunk','frunk']) {
      known[key] = !!data.doors && typeof data.doors[key] === 'boolean'; doors[key] = known[key] ? data.doors[key] : null;
    }
    const pack = battery.voltage !== null && battery.current !== null ? battery.voltage * battery.current / 1000 : number(b.power_kw,-500,500);
    const motor = data.source === 'telemetry_daemon' ? null : number(data.motor_power_kw,-500,500);
    const trip = {};
    for (const [name,key] of Object.entries({distanceKm:'distance_km',durationMin:'duration_min',avgSpeedKmh:'avg_speed_kmh',
      consumptionKwh100:'consumption_kwh_100km',energyKwh:'energy_kwh',distance2Km:'distance2_km'})) trip[name] = number(data.trip && data.trip[key]);
    const hvac = data.hvac_details || {};
    return {battery,doors,known,trip,hvac:{setpoint:number(hvac.setpoint_c,16,30),fan:number(hvac.blower_speed,0,8),
      powerOn:typeof hvac.power_on === 'boolean' ? hvac.power_on : null,airMode:number(hvac.loop_mode,0,2)},
      speed:number(data.speed_kmh,0,300),gear:['P','R','N','D'].includes(data.gear) ? data.gear : null,
      ambientTemp:number(data.ambient_temp_c,-60,80),range:number(b.range_km,0,1000),battTemp:number(b.temp_c,-60,100),
      motorPower:motor !== null ? motor : pack,powerKind:motor !== null ? 'Kuasa motor' : 'Kuasa pek · V × I',
      hvacPower:number(data.hvac_power_w,0,30000),auxPower:number(data.accessories_12v_w,0,30000),
      seatbelt:{driver:number(data.seatbelt && data.seatbelt.driver,0,1),passenger:number(data.seatbelt && data.seatbelt.passenger,0,1)}};
  }
  return {normalize,number};
}));
