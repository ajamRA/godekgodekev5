const {test} = require('node:test');
const assert = require('node:assert/strict');
require('../assets/avas-audio.js');
require('../assets/avas.js');
const audio = globalThis.AvasAudio, wav = globalThis.AvasWav;
function fixture(channels, sampleRate=48000) {
  return {sampleRate, length:channels[0].length, numberOfChannels:channels.length, getChannelData:c=>channels[c]};
}
test('writes PCM16 mono 48kHz and downmixes stereo with clipping protection',()=>{
  const buffer=audio.encode(fixture([[2,-2,1,-1],[2,-2,0,0]]),0,4/48000);
  assert.equal(wav.inspect(buffer).duration_seconds,4/48000);
  const v=new DataView(buffer);
  assert.deepEqual([0,1,2,3].map(i=>v.getInt16(44+2*i,true)),[32767,-32768,16384,-16384]);
});
test('takes samples from the requested part of the song',()=>{
  const buffer=audio.encode(fixture([[0,0,0.5,-0.5,0,0]]),2/48000,2/48000);
  const v=new DataView(buffer);
  assert.equal(buffer.byteLength,48);
  assert.equal(v.getInt16(44,true),16384);assert.equal(v.getInt16(46,true),-16384);
});
test('rejects empty, oversized and out-of-range clips before upload',()=>{
  const f=fixture([new Float32Array(48000).fill(0.1)]);
  for(const [start,duration] of [[-1,1],[0,0],[0,16],[1,1],[NaN,1],[0,Infinity]]) {
    assert.throws(()=>audio.encode(f,start,duration),/Pilih masa|melebihi/);
  }
  assert.throws(()=>audio.encode(fixture([[1]],44100),0,1/48000),/dinyahkod/);
});
test('rejects silence, stereo cancellation, non-finite samples and missing channels',()=>{
  for(const channels of [[[0,0]],[[0.5],[-0.5]],[[NaN]],[[Infinity]]]) {
    assert.throws(()=>audio.encode(fixture(channels),0,channels[0].length/48000),/senyap|tidak sah/);
  }
  assert.throws(()=>audio.encode({sampleRate:48000,length:2,numberOfChannels:2,getChannelData:()=>[1]},0,2/48000),/tidak lengkap/);
});
test('fifteen seconds stays within the selected WAV size and duration contract',()=>{
  const buffer=audio.encode(fixture([new Float32Array(48000*15).fill(0.25)]),0,15);
  assert.equal(buffer.byteLength,1440044);
  assert.equal(wav.inspect(buffer).duration_seconds,15);
});
test('supports a thirteen second selection and refuses longer than fifteen seconds',()=>{
  const f=fixture([new Float32Array(48000*20).fill(0.25)]);
  assert.equal(wav.inspect(audio.encode(f,2,13)).duration_seconds,13);
  assert.throws(()=>audio.encode(f,0,15.001),/15 saat/);
});
test('decoder rejects source limits and gives a clear unsupported-WebView error',async()=>{
  await assert.rejects(audio.decode(new ArrayBuffer(0)),/20 MiB/);
  await assert.rejects(audio.decode(new ArrayBuffer(audio.maxSourceBytes+1)),/20 MiB/);
  await assert.rejects(audio.decode(new ArrayBuffer(2)),/WebView/);
});
