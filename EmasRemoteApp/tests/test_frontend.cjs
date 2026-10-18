const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
for(const file of ['model.js','recorder.js','api-client.js'])vm.runInThisContext(fs.readFileSync(path.join(__dirname,'../assets',file),'utf8'),{filename:file});
const model=global.RemoteModel,VoiceRecorder=global.VoiceRecorder,RemoteApi=global.RemoteApi;
let count=0;const check=(fn)=>{fn();count++;};
check(()=>assert.equal(model.telemetry({soc_pct:null},1000).soc,null));
check(()=>assert.equal(model.telemetry({soc_pct:true},1000).soc,null));
check(()=>assert.equal(model.telemetry({soc_pct:38,updated_at:1000,fresh:true},1005).fresh,true));
check(()=>assert.equal(model.telemetry({soc_pct:38,updated_at:1000,fresh:true},1020).fresh,false));
check(()=>assert.match(model.jobLabel({status:'received'}),/belum disahkan/));
check(()=>assert.match(model.jobLabel({status:'failed'}),/Gagal/));
const recorders=[];global.MediaRecorder=class {
  static isTypeSupported(){return true;}constructor(){this.state='inactive';this.mimeType='audio/webm';recorders.push(this);}
  start(){this.state='recording';}stop(){this.state='inactive';queueMicrotask(()=>this.onstop&&this.onstop());}
};
(async()=>{
  let resolve;let stopped=0;let sent=0;
  const stream=()=>({getTracks:()=>[{stop:()=>stopped++}]});
  let recorder=new VoiceRecorder(()=>{},()=>sent++,e=>{throw e;},{getUserMedia:()=>new Promise(r=>resolve=r)});
  const waiting=recorder.start();recorder.finish();resolve(stream());await waiting;
  check(()=>assert.equal(recorder.state,'idle'));check(()=>assert.equal(stopped,1));check(()=>assert.equal(sent,0));
  recorder=new VoiceRecorder(()=>{},()=>sent++,e=>{throw e;},{getUserMedia:async()=>stream()});
  await recorder.start();const old=recorders.at(-1);recorder.cancel();await recorder.start();
  const fresh=recorders.at(-1);old.onstop();check(()=>assert.equal(recorder.recorder,fresh));check(()=>assert.equal(recorder.state,'recording'));recorder.cancel();
  recorder=new VoiceRecorder(()=>{},()=>sent++,e=>{throw e;},{getUserMedia:async()=>stream()});
  await recorder.start();recorder.started=Date.now()-1000;recorders.at(-1).ondataavailable({data:new Blob(['voice'])});recorder.finish();
  await new Promise(r=>setImmediate(r));check(()=>assert.equal(sent,1));check(()=>assert.equal(recorder.state,'idle'));
  const api=new RemoteApi('fixture-key');global.fetch=async()=>({ok:false,status:500,json:async()=>({detail:'fixture failure'})});
  await assert.rejects(()=>api.write('/test',{preset:'tepi'},'fixture-id'),/fixture failure/);count++;
  check(()=>assert.equal(api.writing,false));
  global.fetch=async()=>({ok:true,status:200,json:async()=>{throw Error('html');}});
  await assert.rejects(()=>api.request('/test'),/Respons pelayan tidak sah/);count++;
  let finish;global.fetch=()=>new Promise(r=>finish=r);const first=api.write('/test',{});
  await assert.rejects(()=>api.write('/test',{}),/masih diproses/);count++;
  finish({ok:true,json:async()=>({ok:true})});await first;
  console.log('PASS '+count+' frontend checks: permission race, recorder cleanup, freshness, JSON errors and serialized writes.');
})().catch(e=>{console.error(e);process.exitCode=1;});
