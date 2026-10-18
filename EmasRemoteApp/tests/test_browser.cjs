const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/user/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assets=path.resolve(__dirname,'../assets'),output=process.env.EMAS_TEST_OUTPUT||path.resolve(__dirname,'../../artifacts/remote-fix-20261009/ui-tests');
fs.mkdirSync(output,{recursive:true});
let runningBrowser;
const server=http.createServer((req,res)=>{
  const requestPath=req.url.split('?')[0];
  const name=requestPath==='/'?'index.html':requestPath.replace(/^\/static\/remote-v2\//,'').replace(/^\//,'');
  if(!['index.html','remote.css','model.js','api-client.js','recorder.js','app.js'].includes(name)){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(path.join(assets,name)));
});
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({channel:'msedge',headless:true});
  runningBrowser=browser;
  const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,deviceScaleFactor:1});
  const page=await context.newPage();const writes=[],errors=[];let failPreset=false,failStatus=false,statusReads=0;
  let fixture={ok:true,version:'2.0',aco_enabled:false,car:{online:true,last_seen:Date.now()/1000,playing:false},
    telemetry:{soc_pct:38,range_km:145,updated_at:Date.now()/1000,fresh:true},megaphone:{enabled:true,remaining_sec:600},
    presets:[{id:'alihkan',label:'Tolong alihkan'},{id:'salam',label:'Assalamualaikum'},{id:'tepi',label:'Beri laluan'}],jobs:[]};
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
    window.__proton=0;window.__stopped=0;
    window.AndroidApp={apiKey:()=> 'fixture',haptic(){},updateTelemetry(){},saveApiKey(){},pinWidget(){},openOfficialProtonApp(){window.__proton++;}};
    Object.defineProperty(navigator,'mediaDevices',{value:{getUserMedia:()=>new Promise(resolve=>window.__resolveMic=()=>resolve({getTracks:()=>[{stop(){window.__stopped++;}}]}))}});
    window.MediaRecorder=class{static isTypeSupported(){return true;}constructor(){this.state='inactive';this.mimeType='audio/webm';}
      start(){this.state='recording';}stop(){this.state='inactive';queueMicrotask(()=>{this.ondataavailable({data:new Blob(['fixture voice'])});this.onstop();});}};
  });
  await page.route('**/api/**',async route=>{
    const request=route.request();if(request.method()!=='GET')writes.push(request.url());
    if(request.url().endsWith('/api/remote/status')){statusReads++;await route.fulfill(failStatus?{status:404,json:{detail:'Status pelayan belum tersedia'}}:{json:fixture});return;}
    if(request.url().endsWith('/switch')){const enabled=request.postDataJSON().enabled;fixture={...fixture,megaphone:{enabled,remaining_sec:enabled?600:0}};await route.fulfill({json:{ok:true,...fixture.megaphone}});return;}
    if(failPreset&&request.url().endsWith('/speak')){await route.fulfill({status:500,json:{detail:'Ujian ralat preset'}});return;}
    await route.fulfill({json:{ok:true,id:'fixture_message_1',status:'queued',queued:true}});
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/');
  await page.waitForFunction(()=>document.getElementById('carState').textContent.includes('IHU aktif'));
  assert.equal(writes.length,0,'Page load must not send a command');
  await page.locator('#proton').click();assert.equal(await page.evaluate(()=>window.__proton),1);
  assert.equal(await page.locator('#presets button').count(),3);
  for(const width of [320,360,390,430]){
    await page.setViewportSize({width,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth),false,'No horizontal overflow at '+width);
    const box=await page.locator('#proton').boundingBox();assert.ok(box.height>=48);
  }
  await page.setViewportSize({width:390,height:844});
  failPreset=true;await page.locator('#presets button').first().click();
  await page.waitForFunction(()=>document.getElementById('messageState').textContent.includes('Ujian ralat preset'));
  assert.ok(!(await page.locator('#messageState').innerText()).includes('Dihantar'));failPreset=false;
  await page.locator('#mic').scrollIntoViewIfNeeded();const mic=await page.locator('#mic').boundingBox();
  await page.mouse.move(mic.x+mic.width/2,mic.y+mic.height/2);await page.mouse.down();
  await page.waitForFunction(()=>!!window.__resolveMic);await page.mouse.up();await page.evaluate(()=>window.__resolveMic());
  await page.waitForFunction(()=>document.getElementById('micTitle').textContent==='Tekan dan tahan');
  assert.equal(writes.filter(url=>url.endsWith('/upload')).length,0);
  assert.ok(await page.evaluate(()=>window.__stopped)>0);
  await page.mouse.down();await page.waitForFunction(()=>document.getElementById('micTitle').textContent==='Benarkan mikrofon…');
  await page.evaluate(()=>window.__resolveMic());await page.waitForFunction(()=>document.getElementById('micTitle').textContent==='Sedang merakam');
  await page.waitForTimeout(450);await page.mouse.up();
  await page.waitForFunction(()=>document.getElementById('micTitle').textContent==='Tekan dan tahan');
  assert.equal(writes.filter(url=>url.endsWith('/upload')).length,1);
  await page.mouse.down();await page.waitForFunction(()=>document.getElementById('micTitle').textContent==='Benarkan mikrofon…');
  await page.evaluate(()=>window.__resolveMic());await page.waitForFunction(()=>document.getElementById('micTitle').textContent==='Sedang merakam');
  await page.evaluate(()=>window.remotePause());await page.mouse.up();
  assert.equal(await page.locator('#micTitle').innerText(),'Tekan dan tahan');
  assert.equal(writes.filter(url=>url.endsWith('/upload')).length,1);
  fixture={...fixture,car:{online:false,last_seen:null,playing:null},telemetry:{soc_pct:null,range_km:null,updated_at:null,fresh:false},megaphone:{enabled:false,remaining_sec:0},jobs:[]};
  await page.evaluate(()=>window.remoteResume());
  await page.waitForFunction(()=>document.getElementById('carState').textContent.includes('tiada heartbeat'));
  assert.equal(await page.locator('#soc').innerText(),'—');assert.ok(await page.locator('#mic').isDisabled());
  assert.ok(await page.locator('#telemetryCard').isHidden());
  assert.match(await page.locator('#controlHint').innerText(),/Kereta belum bersambung/);
  assert.ok(await page.locator('#switch').isEnabled(),'Activation must remain available when car sleeps');
  const beforeRefresh=writes.length,beforeReads=statusReads;
  await page.locator('#refreshConnection').click();await page.waitForFunction(()=>document.getElementById('toast').textContent.includes('Menyemak'));
  await page.waitForTimeout(150);assert.ok(statusReads>beforeReads);assert.equal(writes.length,beforeRefresh,'Refresh must only read status');
  await page.locator('#switch').click();await page.waitForFunction(()=>document.getElementById('switch').getAttribute('aria-pressed')==='true');
  assert.ok(await page.locator('#mic').isDisabled(),'Activation must not falsely mark sleeping IHU ready');
  assert.match(await page.locator('#toast').innerText(),/tidak membangunkan kereta/);
  fixture={...fixture,car:{online:true,last_seen:Date.now()/1000,playing:false},jobs:[{id:'fixture_message_1',status:'received'}]};
  await page.locator('#refreshConnection').click();await page.waitForFunction(()=>!document.getElementById('presets').querySelector('button').disabled);
  failPreset=true;await page.locator('#presets button').first().click();
  await page.waitForFunction(()=>document.getElementById('messageState').textContent.includes('Ujian ralat preset'));
  await page.locator('#refreshConnection').click();await page.waitForTimeout(150);
  assert.match(await page.locator('#messageState').innerText(),/Ujian ralat preset/,'An older receipt must not replace a new submission error');
  failPreset=false;await page.locator('#presets button').first().click();await page.waitForFunction(()=>document.getElementById('messageState').textContent.includes('Mesej beratur'));
  fixture={...fixture,jobs:[{id:'fixture_message_1',status:'failed'}]};
  await page.locator('#refreshConnection').click();await page.waitForFunction(()=>document.getElementById('messageState').textContent.includes('Gagal'));
  assert.ok(await page.locator('#messageState').evaluate(e=>e.classList.contains('error')));
  failStatus=true;await page.locator('#refreshConnection').click();await page.waitForFunction(()=>document.getElementById('vpsState').textContent.includes('belum dapat'));
  assert.ok(await page.locator('#switch').isDisabled());assert.match(await page.locator('#controlHint').innerText(),/VPS belum dapat dicapai/);
  failStatus=false;await page.locator('#refreshConnection').click();await page.waitForFunction(()=>document.getElementById('vpsState').textContent.includes('tersedia'));
  assert.equal(errors.length,0,errors.join('\n'));
  fixture={...fixture,car:{online:false,last_seen:null,playing:null}};
  await page.goto('http://127.0.0.1:'+server.address().port+'/');
  await page.waitForFunction(()=>document.getElementById('carState').textContent.includes('tiada heartbeat'));
  await page.screenshot({path:path.join(output,'remote-v2-mobile.png'),fullPage:true});
  console.log('PASS browser: 4 mobile widths, Proton shortcut, no startup writes, failed preset, permission race, upload, pause cleanup, offline reasons, read-only refresh, sleeping-car activation, failed status and recovery.');
  await browser.close();server.close();
})().catch(async error=>{console.error(error);if(runningBrowser)await runningBrowser.close();server.close();process.exitCode=1;});
