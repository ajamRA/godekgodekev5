const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
require('../assets/avas.js');
const avas = globalThis.AvasWav;
function fixture({rate=48000, channels=1, bits=16, silent=false}={}) {
  const b=Buffer.alloc(52);b.write('RIFF');b.writeUInt32LE(44,4);b.write('WAVEfmt ',8);
  b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(channels,22);
  b.writeUInt32LE(rate,24);b.writeUInt32LE(rate*channels*bits/8,28);
  b.writeUInt16LE(channels*bits/8,32);b.writeUInt16LE(bits,34);b.write('data',36);b.writeUInt32LE(8,40);
  if(!silent)b.writeInt16LE(1500,44);
  return b;
}
const ab = b => b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength);
test('accepts complete original vendor WAVs with metadata chunks',()=>{
  for(const [slot,name] of [[1,'yinheyinfu'],[2,'space'],[3,'pixel']]){
    const b=fs.readFileSync(path.resolve(__dirname,`../../artifacts/avas-research-20261015/vendor/etc/avas/sound_type_${slot}/${name}.wav`));
    assert.ok(avas.inspect(ab(b)).duration_seconds>1);
  }
});
test('rejects stereo, wrong rate, wrong bit depth and silent WAV',()=>{
  for(const cfg of [{channels:2},{rate:44100},{bits:32},{silent:true}])assert.throws(()=>avas.inspect(ab(fixture(cfg))));
});
test('rejects truncated data and duplicate audio chunks',()=>{
  const b=fixture();assert.throws(()=>avas.inspect(ab(b.subarray(0,b.length-1))));
  const duplicate=Buffer.concat([b,b.subarray(36)]);duplicate.writeUInt32LE(duplicate.length-8,4);
  assert.throws(()=>avas.inspect(ab(duplicate)));
});
test('rejects a duration longer than sixty seconds and oversized files',()=>{
  const b=Buffer.alloc(44+96000*60+2);fixture().copy(b,0,0,44);b.writeUInt32LE(b.length-8,4);b.writeUInt32LE(b.length-44,40);b[44]=1;
  assert.throws(()=>avas.inspect(ab(b)));
  assert.throws(()=>avas.inspect(new ArrayBuffer(8*1024*1024+1)));
});
test('status labels distinguish a daemon result from physical playback',()=>{
  assert.match(avas.jobLabel({status:'applied'}),/fizikal belum disahkan/);
  assert.match(avas.jobLabel({status:'uncertain'}),/tidak diulang/);
  assert.match(avas.jobLabel({status:'queued'}),/Menunggu IHU/);
});
