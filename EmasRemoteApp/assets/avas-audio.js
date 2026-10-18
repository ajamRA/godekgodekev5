(function (root) {
  'use strict';
  const RATE = 48000, MAX_SECONDS = 15, MAX_SOURCE_BYTES = 20 * 1024 * 1024;
  function encode(audio, start, duration) {
    if (!audio || audio.sampleRate !== RATE || !Number.isInteger(audio.length) || audio.length < 1 ||
        !Number.isInteger(audio.numberOfChannels) || audio.numberOfChannels < 1 || audio.numberOfChannels > 8) {
      throw Error('Audio yang dinyahkod tidak disokong.');
    }
    start = Number(start); duration = Number(duration);
    if (!Number.isFinite(start) || !Number.isFinite(duration) || start < 0 || duration <= 0 || duration > MAX_SECONDS) {
      throw Error('Pilih masa mula yang sah dan panjang sehingga 15 saat.');
    }
    const first = Math.round(start * RATE), frames = Math.round(duration * RATE);
    if (frames < 1 || first + frames > audio.length) throw Error('Bahagian yang dipilih melebihi panjang lagu.');
    const channels = [];
    for (let c = 0; c < audio.numberOfChannels; c++) {
      const samples = audio.getChannelData(c);
      if (!samples || samples.length < audio.length) throw Error('Data audio tidak lengkap.');
      channels.push(samples);
    }
    const buffer = new ArrayBuffer(44 + frames * 2), view = new DataView(buffer);
    function tag(offset, value) { for (let n = 0; n < value.length; n++) view.setUint8(offset + n, value.charCodeAt(n)); }
    tag(0, 'RIFF'); view.setUint32(4, buffer.byteLength - 8, true); tag(8, 'WAVE');
    tag(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, RATE, true); view.setUint32(28, RATE * 2, true); view.setUint16(32, 2, true);
    view.setUint16(34, 16, true); tag(36, 'data'); view.setUint32(40, frames * 2, true);
    let audible = false;
    for (let i = 0; i < frames; i++) {
      let sample = 0;
      for (const channel of channels) {
        const value = channel[first + i];
        if (!Number.isFinite(value)) throw Error('Data audio mengandungi nilai tidak sah.');
        sample += value / channels.length;
      }
      sample = Math.max(-1, Math.min(1, sample));
      const pcm = Math.round(sample * (sample < 0 ? 32768 : 32767));
      view.setInt16(44 + i * 2, pcm, true);
      if (pcm !== 0) audible = true;
    }
    if (!audible) throw Error('Bahagian yang dipilih senyap selepas ditukar kepada mono. Pilih bahagian lain.');
    return buffer;
  }
  async function decode(buffer) {
    if (!(buffer instanceof ArrayBuffer) || !buffer.byteLength || buffer.byteLength > MAX_SOURCE_BYTES) {
      throw Error('Pilih fail audio berukuran maksimum 20 MiB.');
    }
    const Context = root.OfflineAudioContext || root.webkitOfflineAudioContext;
    if (!Context) throw Error('Penukaran audio tidak disokong. Kemas kini Android System WebView atau pilih WAV 48 kHz mono 16-bit.');
    let audio;
    try {
      const context = new Context(1, 1, RATE);
      audio = await context.decodeAudioData(buffer.slice(0));
    } catch (_) {
      throw Error('Audio tidak dapat dibaca pada telefon ini. Cuba MP3, M4A atau WAV tanpa perlindungan.');
    }
    if (audio.sampleRate !== RATE || !Number.isFinite(audio.duration) || audio.duration <= 0 || audio.duration > 600 ||
        audio.numberOfChannels < 1 || audio.numberOfChannels > 8) {
      throw Error('Gunakan audio sehingga 10 minit dengan maksimum 8 saluran.');
    }
    return audio;
  }
  root.AvasAudio = {encode, decode, maxSourceBytes: MAX_SOURCE_BYTES};
  if (typeof module !== 'undefined') module.exports = root.AvasAudio;
})(typeof window !== 'undefined' ? window : globalThis);
