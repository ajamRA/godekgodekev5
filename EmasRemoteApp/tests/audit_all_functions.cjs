// Audit only: real APK web assets, fully intercepted server and simulated Android/audio.
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const ROOT=path.resolve(__dirname,'../..'),ASSETS=path.join(ROOT,'EmasRemoteApp/assets');
const OUT=path.join(ROOT,'artifacts/remote-function-audit-20261016');
const results=[];
const pause=ms=>new Promise(r=>setTimeout(r,ms));
let browser;
async function fixture(cfg={}){
  const context=await browser.newContext({viewport:{width:390,height:844},reducedMotion:'reduce'});
  const page=await context.newPage(),errors=[],requests=[];
  const state={enabled:false,lockActive:'mario',lockSounds:{mario:{name:'Mario',url:'/static/locksounds/mario.wav'}},
    horns:[{id:'h1',name:cfg.apostrophe?"Hon O'Brien":'Hon Saya',audio_url:'/static/megafon/custom/h1.wav'}],
    presets:[{id:'alihkan',tag:'Tepi',name:'Alihkan kereta'},{id:'salam',tag:'Salam',name:'Assalamualaikum'}]};
  if(cfg.lockApostrophe)state.lockSounds.owner={name:"Nada O'Brien",url:'/static/locksounds/owner.wav'};
  page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(options=>{
    if(options.fastCap){const timeout=window.setTimeout.bind(window);window.setTimeout=(fn,ms,...args)=>timeout(fn,ms===12000?450:ms,...args);}
    localStorage.setItem('emas_api_key','fixture-key');
    window.__native=[],window.__previews=[],window.__tracksStopped=0;
    window.__deliverNative=()=>window.onNativeAudioRecorded(btoa('fixture-audio'),'audio/mp4');
    if(options.native!==false){
      let recording=false;
      window.AndroidApp={apiKey:()=> 'fixture-key',haptic(){},saveApiKey:k=>window.__native.push(['key',k]),
        pinWidget:()=>window.__native.push(['widget']),openOfficialProtonApp:()=>window.__native.push(['proton']),
        openAppSettings:()=>window.__native.push(['settings']),requestMic:()=>window.__native.push(['permission']),
        hasMicPermission:()=>options.permission!==false,checkMicStatus:()=>'{"os_granted":true}',
        updateTelemetry:json=>window.__native.push(['telemetry',JSON.parse(json)]),isNativeRecorderSupported:()=>true,
        startRecording(){window.__native.push(['start']);if(options.permission!==false)recording=true;},
        stopRecording(){window.__native.push(['stop']);if(recording){recording=false;if(options.autoNative!==false)queueMicrotask(window.__deliverNative);}},
        cancelRecording(){window.__native.push(['cancel']);recording=false;}};
    }
    const stream=()=>{const track={label:'Fixture microphone',muted:false,readyState:'live',stop(){window.__tracksStopped++;}};return{getTracks:()=>[track],getAudioTracks:()=>[track]};};
    Object.defineProperty(navigator,'mediaDevices',{value:{
      enumerateDevices:async()=>[{kind:'audioinput',label:'Fixture mic',deviceId:'fixture-device'}],
      getUserMedia:async constraints=>{
        if(options.denyWeb)throw new DOMException('Fixture denied','NotAllowedError');
        if(options.rawFails&&typeof constraints.audio==='object')throw new DOMException('Raw unavailable','OverconstrainedError');
        if(options.delayMic)return new Promise(resolve=>window.__grantMic=()=>resolve(stream()));
        return stream();
      }}});
    window.MediaRecorder=class{
      static isTypeSupported(){return true;}
      constructor(){this.state='inactive';this.mimeType='audio/webm';}
      start(){this.state='recording';}
      stop(){this.state='inactive';queueMicrotask(()=>{this.ondataavailable?.({data:new Blob(['fixture-audio'],{type:'audio/webm'})});this.onstop?.();});}
    };
    window.Audio=class{constructor(url){this.url=url;}play(){window.__previews.push(this.url);return Promise.resolve();}pause(){}};
    document.execCommand=()=>true;
  },cfg);
  page.on('dialog',dialog=>dialog.accept());
  await page.route('**/*',async route=>{
    const request=route.request(),url=new URL(request.url()),name=path.basename(url.pathname),method=request.method();
    if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(ASSETS,'index.html'))});
    if(fs.existsSync(path.join(ASSETS,name))&&fs.statSync(path.join(ASSETS,name)).isFile())return route.fulfill({contentType:name.endsWith('.css')?'text/css':'application/javascript',body:fs.readFileSync(path.join(ASSETS,name))});
    let body=null;try{body=request.postDataJSON();}catch{}
    requests.push({path:url.pathname,method,body,authorized:request.headers()['x-api-key']==='fixture-key'});
    const send=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
    if(cfg.failure===url.pathname)return send({ok:false,error:'Fixture failure'},500);
    if(cfg.okFalse===url.pathname)return send({ok:false,error:'Rejected fixture'},200);
    if(cfg.html===url.pathname)return route.fulfill({contentType:'text/html',body:'<html>error</html>'});
    if(cfg.drop===url.pathname)return route.abort();
    if(cfg.delay===url.pathname&&method==='POST')await pause(350);
    if(url.pathname==='/api/remote/status')return send({ok:true,car:{online:false},megaphone:{enabled:state.enabled,remaining_sec:state.enabled?600:0},telemetry:{}});
    if(url.pathname.endsWith('/avas/state'))return send({ok:true,enabled:false,ready:false,device_config:null,job:null});
    if(url.pathname==='/api/megafon/switch'){state.enabled=body.enabled;return send({ok:true});}
    if(url.pathname==='/api/locksound/list')return send({ok:true,sounds:state.lockSounds,active:state.lockActive});
    if(url.pathname==='/api/locksound/set'){state.lockActive=body.sound;return send({ok:true});}
    if(url.pathname==='/api/locksound/delete'){delete state.lockSounds[body.sound];return send({ok:true});}
    if(url.pathname==='/api/locksound/upload'){state.lockSounds.uploaded={name:'Nada Baru',url:'/static/locksounds/uploaded.wav'};return send({ok:true,key:'uploaded'});}
    if(url.pathname==='/api/megafon/custom/list')return send({ok:true,horns:state.horns});
    if(url.pathname==='/api/megafon/custom/delete'){state.horns=state.horns.filter(h=>h.id!==body.id);return send({ok:true});}
    if(url.pathname==='/api/megafon/custom/upload'){state.horns.push({id:'h2',name:'Hon Baru',audio_url:'/static/megafon/custom/h2.wav'});return send({ok:true});}
    if(url.pathname==='/api/megafon/presets/list')return send({ok:true,presets:state.presets});
    if(url.pathname==='/api/megafon/presets/save'){state.presets=body.presets;return send({ok:true});}
    return send({ok:true,status:'queued',id:'fixture-job',playback_verified:false});
  });
  await page.goto('https://fixture.test/');
  await page.waitForFunction(()=>document.querySelectorAll('.preset-chip').length===2&&document.querySelectorAll('.btn-blast-horn').length===1);
  return {page,state,errors,requests,context,writes:()=>requests.filter(r=>r.method!=='GET'),native:()=>page.evaluate(()=>window.__native)};
}
async function audit(id,area,name,check,cfg={}){
  if(process.env.AUDIT_IDS&&!process.env.AUDIT_IDS.split(',').includes(id))return;
  let f;
  try{f=await fixture(cfg);await check(f);results.push({id,area,name,status:'PASS'});}
  catch(error){results.push({id,area,name,status:'FAIL',evidence:String(error.message).slice(0,550),page_errors:f?.errors||[]});}
  finally{if(f)await f.context.close();}
  console.log(results.at(-1).status,id,name);
}
const click=async(f,selector)=>{await f.page.locator(selector).click();await pause(70);};
async function pressMic(f,hold=420){const mic=f.page.locator('#micBtn');await mic.scrollIntoViewIfNeeded();const b=await mic.boundingBox();await f.page.mouse.move(b.x+b.width/2,b.y+b.height/2);await f.page.mouse.down();await pause(hold);await f.page.mouse.up();await pause(100);}
const toast=f=>f.page.locator('#toast').textContent();
const posted=(f,path)=>f.writes().filter(r=>r.path===path);
(async()=>{
  browser=await chromium.launch({headless:true});
  try{
    await audit('A01','startup','Startup is read-only and all GETs authenticated',async f=>{assert.equal(f.writes().length,0);assert.ok(f.requests.every(r=>r.authorized));assert.deepEqual(f.errors,[]);});
    await audit('A02','settings','Open and close settings',async f=>{await click(f,'#settingsToggle');assert.ok(await f.page.locator('#settingsModal').isVisible());await click(f,'#settingsModal .btn-modal-close');assert.equal(await f.page.locator('#settingsModal').isVisible(),false);});
    await audit('A03','settings','Save native access key calls Android storage',async f=>{await click(f,'#settingsToggle');await f.page.locator('#apiKeyInput').fill('fixture-new');await click(f,'button[onclick="saveApiKey()"]');assert.ok((await f.native()).some(x=>x[0]==='key'&&x[1]==='fixture-new'));});
    await audit('A04','settings','Empty access key is refused',async f=>{await click(f,'#settingsToggle');await f.page.locator('#apiKeyInput').fill(' ');await click(f,'button[onclick="saveApiKey()"]');assert.match(await toast(f),/Masukkan/);assert.equal((await f.native()).filter(x=>x[0]==='key').length,0);});
    await audit('A05','widget','Pin widget bridge',async f=>{await click(f,'#settingsToggle');await click(f,'button[onclick="pinWidgetToHome()"]');assert.ok((await f.native()).some(x=>x[0]==='widget'));});
    await audit('A06','proton','Open official Proton app bridge',async f=>{await click(f,'.btn-proton-app');assert.ok((await f.native()).some(x=>x[0]==='proton'));});
    await audit('M01','megaphone','Enable megaphone for 10 minutes',async f=>{await click(f,'#btnMegaToggle');assert.equal(posted(f,'/api/megafon/switch')[0].body.enabled,true);assert.match(await f.page.locator('#btnMegaToggle').textContent(),/Aktif/);});
    await audit('M02','megaphone','Press active switch turns megaphone OFF',async f=>{await click(f,'#btnMegaToggle');await click(f,'#btnMegaToggle');assert.equal(posted(f,'/api/megafon/switch').at(-1).body.enabled,false);});
    await audit('M03','megaphone','HTTP failure is visible when enabling',async f=>{await click(f,'#btnMegaToggle');assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')),'failure must be shown');},{failure:'/api/megafon/switch'});
    await audit('M04','megaphone','Repeated clicks while switch request is pending are serialized',async f=>{await f.page.locator('#btnMegaToggle').dblclick();await pause(500);assert.equal(posted(f,'/api/megafon/switch').length,1);},{delay:'/api/megafon/switch'});
    await audit('L01','lock','Factory and custom sounds list renders',async f=>{assert.equal(await f.page.locator('#lockGrid .sound-card').count(),9);});
    await audit('L02','lock','Preview plays only on phone',async f=>{await click(f,'.sound-library summary');await click(f,'#lockGrid .btn-preview >> nth=0');assert.equal(await f.page.evaluate(()=>window.__previews.length),1);assert.equal(f.writes().length,0);});
    await audit('L03','lock','Choose lock sound sends correct selection',async f=>{await click(f,'.sound-library summary');await click(f,'#lockGrid .btn-apply-sound >> nth=1');assert.equal(posted(f,'/api/locksound/set')[0].body.sound,'guru');});
    await audit('L04','lock','Lock upload requires a file',async f=>{await click(f,'button[onclick="submitLockUpload()"]');assert.equal(f.writes().length,0);assert.match(await toast(f),/pilih fail/);});
    await audit('L05','lock','Lock file pick, upload and auto-apply',async f=>{await f.page.locator('#lockFileInput').setInputFiles({name:'Nada Baru.wav',mimeType:'audio/wav',buffer:Buffer.from('fixture')});await click(f,'button[onclick="submitLockUpload()"]');assert.equal(posted(f,'/api/locksound/upload').length,1);assert.equal(posted(f,'/api/locksound/set')[0].body.sound,'uploaded');});
    await audit('L06','lock','Delete sound updates local hidden list',async f=>{await click(f,'.sound-library summary');await click(f,'#lockGrid .btn-delete-sound >> nth=0');assert.equal(posted(f,'/api/locksound/delete').length,1);assert.ok(await f.page.evaluate(()=>JSON.parse(localStorage.getItem('hidden_lock_sounds')).includes('mario')));});
    await audit('L07','lock','Apostrophe in custom sound name does not break preview',async f=>{await click(f,'.sound-library summary');await click(f,'#lockGrid .sound-card:last-child .btn-preview');assert.deepEqual(f.errors,[]);assert.equal(await f.page.evaluate(()=>window.__previews.length),1);},{lockApostrophe:true});
    await audit('L08','lock','Delete controls have a visible label or icon',async f=>{const labels=await f.page.locator('#lockGrid .btn-delete-sound').evaluateAll(els=>els.map(el=>el.textContent.trim()||el.querySelector('svg')));assert.ok(labels.every(Boolean),'blank delete buttons');});
    await audit('H01','horn','Custom horn list and badge',async f=>{assert.equal(await f.page.locator('#hornCountBadge').textContent(),'1 Hon');});
    await audit('H02','horn','Horn click sends playback request',async f=>{await click(f,'.btn-blast-horn');assert.deepEqual(f.errors,[]);assert.equal(posted(f,'/api/megafon/speak').length,1);});
    await audit('H03','horn','Horn upload requires a file',async f=>{await click(f,'button[onclick="submitHornUpload()"]');assert.equal(f.writes().length,0);assert.match(await toast(f),/pilih fail/);});
    await audit('H04','horn','Horn file pick and upload refresh list',async f=>{await f.page.locator('#hornFileInput').setInputFiles({name:'Hon Baru.wav',mimeType:'audio/wav',buffer:Buffer.from('fixture')});await click(f,'button[onclick="submitHornUpload()"]');assert.equal(posted(f,'/api/megafon/custom/upload').length,1);assert.equal(await f.page.locator('.btn-blast-horn').count(),2);});
    await audit('H05','horn','Delete custom horn refreshes list',async f=>{await click(f,'.horn-card-wrapper .btn-delete-sound');assert.equal(posted(f,'/api/megafon/custom/delete')[0].body.id,'h1');assert.equal(await f.page.locator('.btn-blast-horn').count(),0);});
    await audit('H06','horn','Apostrophe in horn name leaves a valid click handler',async f=>{await click(f,'.btn-blast-horn');assert.deepEqual(f.errors,[]);},{apostrophe:true});
    await audit('H07','horn','Clearing file selection prevents uploading an old horn file',async f=>{await f.page.locator('#hornFileInput').setInputFiles({name:'old.wav',mimeType:'audio/wav',buffer:Buffer.from('fixture')});await f.page.locator('#hornFileInput').setInputFiles([]);await click(f,'button[onclick="submitHornUpload()"]');assert.equal(posted(f,'/api/megafon/custom/upload').length,0);});
    await audit('P01','presets','Existing preset sends correct ID',async f=>{await click(f,'.preset-chip >> nth=0');assert.equal(posted(f,'/api/megafon/speak')[0].body.preset,'alihkan');});
    await audit('P02','presets','Edit tag/name and add/remove slot in setup',async f=>{await click(f,'button[onclick="openPresetSetup()"]');await click(f,'button[onclick="addPresetSlot()"]');assert.equal(await f.page.locator('#presetSlotsContainer > div').count(),3);const input=f.page.locator('#presetSlotsContainer > div:last-child input').nth(1);await input.fill('Suara ujian');await input.blur();assert.match(await f.page.locator('.preset-chip').last().textContent(),/Suara ujian/);await click(f,'#presetSlotsContainer > div:last-child button');assert.equal(await f.page.locator('.preset-chip').count(),2);});
    await audit('P03','presets','Save & Close persists changes after reload',async f=>{await click(f,'button[onclick="openPresetSetup()"]');await f.page.locator('#presetSlotsContainer > div:first-child input').nth(1).fill('Ayat baru');await f.page.locator('#presetSlotsContainer > div:first-child input').nth(1).blur();await click(f,'button[onclick="closePresetSetup()"] >> nth=1');await f.page.reload();await f.page.locator('.preset-chip').first().waitFor();assert.match(await f.page.locator('.preset-chip').first().textContent(),/Ayat baru/);});
    await audit('P04','presets','HTTP 200 ok:false is not shown as success',async f=>{await click(f,'.preset-chip >> nth=0');assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{okFalse:'/api/megafon/speak'});
    await audit('P05','presets','Preset HTTP 500 reports failure',async f=>{await click(f,'.preset-chip >> nth=0');assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{failure:'/api/megafon/speak'});
    await audit('P06','presets','Repeated preset click while pending sends once',async f=>{await f.page.locator('.preset-chip').first().dblclick();await pause(500);assert.equal(posted(f,'/api/megafon/speak').length,1);},{delay:'/api/megafon/speak'});
    await audit('R01','recorder','Native press-and-release uploads once',async f=>{await pressMic(f);assert.equal(posted(f,'/api/megafon/upload').length,1);});
    await audit('R02','recorder','Short native tap does not upload',async f=>{await pressMic(f,60);assert.equal(posted(f,'/api/megafon/upload').length,0);});
    await audit('R03','recorder','Cancel returns to idle and does not upload',async f=>{await f.page.locator('#micBtn').dispatchEvent('pointerdown',{pointerId:1,button:0});await pause(100);await f.page.evaluate(()=>window.remotePause());assert.equal(posted(f,'/api/megafon/upload').length,0);assert.equal(await f.page.locator('#micBtn').evaluate(el=>el.classList.contains('recording')),false);});
    await audit('R04','recorder','Late native callback after cancellation cannot upload',async f=>{await f.page.locator('#micBtn').dispatchEvent('pointerdown',{pointerId:1,button:0});await f.page.evaluate(()=>window.remotePause());await f.page.evaluate(()=>window.__deliverNative());await pause(100);assert.equal(posted(f,'/api/megafon/upload').length,0);});
    await audit('R05','recorder','Duplicate native callback is consumed once',async f=>{await pressMic(f);await f.page.evaluate(()=>window.__deliverNative());await pause(100);assert.equal(posted(f,'/api/megafon/upload').length,1);});
    await audit('R06','recorder','No permission does not falsely show active native recording',async f=>{await f.page.locator('#micBtn').dispatchEvent('pointerdown',{pointerId:1,button:0});await pause(100);assert.equal(await f.page.locator('#micBtn').evaluate(el=>el.classList.contains('recording')),false);},{permission:false});
    await audit('R07','recorder','Web recorder records, releases tracks and uploads once',async f=>{await pressMic(f);assert.equal(posted(f,'/api/megafon/upload').length,1);assert.ok(await f.page.evaluate(()=>window.__tracksStopped>0));},{native:false});
    await audit('R08','recorder','Web raw constraints fall back to generic microphone',async f=>{await pressMic(f);assert.equal(posted(f,'/api/megafon/upload').length,1);},{native:false,rawFails:true});
    await audit('R09','recorder','Release before permission resolves cancels without upload',async f=>{await pressMic(f,60);await f.page.evaluate(()=>window.__grantMic());await pause(100);assert.equal(posted(f,'/api/megafon/upload').length,0);assert.ok(await f.page.evaluate(()=>window.__tracksStopped>0));},{native:false,delayMic:true});
    await audit('R10','recorder','Denied web microphone shows error and stays idle',async f=>{await pressMic(f);assert.equal(posted(f,'/api/megafon/upload').length,0);assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{native:false,denyWeb:true});
    await audit('R11','recorder','Upload HTTP 200 ok:false is reported as failure',async f=>{await pressMic(f);assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{okFalse:'/api/megafon/upload'});
    await audit('R12','recorder','Native recorder error returns to idle',async f=>{await f.page.locator('#micBtn').dispatchEvent('pointerdown',{pointerId:1,button:0});await f.page.evaluate(()=>window.onNativeAudioError('Fixture recorder error'));assert.equal(await f.page.locator('#micBtn').evaluate(el=>el.classList.contains('recording')),false);assert.match(await toast(f),/Fixture recorder error/);});
    await audit('D01','diagnostics','Permission, settings, log copy and clear use intended local hooks',async f=>{await click(f,'#btnToggleDebug');await click(f,'button[onclick="reRequestMic()"]');await click(f,'button[onclick="openPhoneSettings()"]');await click(f,'button[onclick="copyDebugLog()"]');assert.ok((await f.native()).some(x=>x[0]==='settings'));await click(f,'button[onclick="clearDebugLog()"]');assert.match(await f.page.locator('#debugLogBox').textContent(),/Log dikosongkan/);assert.equal(f.writes().length,0);});
    await audit('D02','diagnostics','Running diagnostics after prior recording never uploads audio',async f=>{await pressMic(f);const before=posted(f,'/api/megafon/upload').length;await f.page.evaluate(()=>window.runDiagnostics());await pause(100);assert.equal(posted(f,'/api/megafon/upload').length,before);});
    await audit('T01','telemetry','Missing telemetry is not advertised as a fresh locked vehicle',async f=>{const t=(await f.native()).find(x=>x[0]==='telemetry')[1];assert.ok(!(t.locked===true&&t.updated_at>0),'missing lock/status replaced with locked:true and current timestamp');});
    await audit('X01','errors','Lost preset response is not automatically retried',async f=>{await click(f,'.preset-chip >> nth=0');await pause(400);assert.equal(posted(f,'/api/megafon/speak').length,1);assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{drop:'/api/megafon/speak'});
    await audit('X02','errors','Malformed lock response does not crash other UI',async f=>{assert.deepEqual(f.errors,[]);assert.equal(await f.page.locator('.preset-chip').count(),2);},{html:'/api/locksound/list'});
    await audit('A07','settings','Browser access key persists in local storage',async f=>{await click(f,'#settingsToggle');await f.page.locator('#apiKeyInput').fill('fixture-new');await click(f,'button[onclick="saveApiKey()"]');assert.equal(await f.page.evaluate(()=>localStorage.getItem('emas_api_key')),'fixture-new');},{native:false});
    await audit('A08','widget','Browser widget button explains APK requirement',async f=>{await click(f,'#settingsToggle');await click(f,'button[onclick="pinWidgetToHome()"]');assert.match(await toast(f),/APK Android/);assert.equal(f.writes().length,0);},{native:false});
    await audit('L09','lock','Clearing lock file selection cannot upload an old file',async f=>{await f.page.locator('#lockFileInput').setInputFiles({name:'old.wav',mimeType:'audio/wav',buffer:Buffer.from('fixture')});await f.page.locator('#lockFileInput').setInputFiles([]);await click(f,'button[onclick="submitLockUpload()"]');assert.equal(posted(f,'/api/locksound/upload').length,0);});
    await audit('L10','lock','Dismissed lock deletion sends no command',async f=>{f.page.removeAllListeners('dialog');f.page.on('dialog',d=>d.dismiss());await click(f,'.sound-library summary');await click(f,'#lockGrid .btn-delete-sound >> nth=0');assert.equal(f.writes().length,0);});
    await audit('L11','lock','Failed lock upload reports failure without auto-apply',async f=>{await f.page.locator('#lockFileInput').setInputFiles({name:'a.wav',mimeType:'audio/wav',buffer:Buffer.from('fixture')});await click(f,'button[onclick="submitLockUpload()"]');assert.equal(posted(f,'/api/locksound/set').length,0);assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{failure:'/api/locksound/upload'});
    await audit('H08','horn','Failed horn upload reports failure',async f=>{await f.page.locator('#hornFileInput').setInputFiles({name:'a.wav',mimeType:'audio/wav',buffer:Buffer.from('fixture')});await click(f,'button[onclick="submitHornUpload()"]');assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{failure:'/api/megafon/custom/upload'});
    await audit('H09','horn','Dismissed horn deletion sends no command',async f=>{f.page.removeAllListeners('dialog');f.page.on('dialog',d=>d.dismiss());await click(f,'.horn-card-wrapper .btn-delete-sound');assert.equal(f.writes().length,0);});
    await audit('R13','recorder','Native twelve-second timer finishes once (accelerated clock)',async f=>{await pressMic(f,650);assert.equal(posted(f,'/api/megafon/upload').length,1);assert.equal((await f.native()).filter(x=>x[0]==='stop').length,1);},{fastCap:true});
    await audit('R14','recorder','Upload HTTP 500 shows error',async f=>{await pressMic(f);assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{failure:'/api/megafon/upload'});
    await audit('R15','recorder','Native pagehide cancels recording without upload',async f=>{await f.page.locator('#micBtn').dispatchEvent('pointerdown',{pointerId:1,button:0});await f.page.evaluate(()=>window.dispatchEvent(new Event('pagehide')));assert.equal(posted(f,'/api/megafon/upload').length,0);assert.ok((await f.native()).some(x=>x[0]==='cancel'));});
    await audit('D03','diagnostics','Standalone diagnostics never upload recording',async f=>{await f.page.evaluate(()=>window.runDiagnostics());await pause(100);assert.equal(posted(f,'/api/megafon/upload').length,0);});
    await audit('P07','presets','HTML response is not shown as accepted speech',async f=>{await click(f,'.preset-chip >> nth=0');assert.ok(await f.page.locator('#toast').evaluate(el=>el.classList.contains('error')));},{html:'/api/megafon/speak'});
  }finally{await browser.close();}
  const report={target:'APK 2.13 web assets',mode:'laptop-browser with intercepted HTTP and simulated Android/audio',vehicle_commands:0,real_network_requests:0,
    total:results.length,passed:results.filter(x=>x.status==='PASS').length,failed:results.filter(x=>x.status==='FAIL').length,results};
  fs.writeFileSync(path.join(OUT,process.env.AUDIT_IDS?'ui-additional.json':'ui-audit.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({total:report.total,passed:report.passed,failed:report.failed}));
})().catch(e=>{console.error(e);process.exitCode=1;});
