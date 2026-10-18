const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/user/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const repo=path.resolve(__dirname,'../..'),release=path.join(repo,'artifacts/remote-fix-20261009/ui-tests');
const key=process.env.EMAS_API_KEY;
assert.ok(key,'Supply the effective VPS credential privately through EMAS_API_KEY.');
(async()=>{
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try{
    const page=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:1});
    const errors=[],writes=[],urls=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/*',route=>{
      const request=route.request();urls.push(request.url());
      if(request.method()!=='GET'){writes.push(request.method());return route.abort();}
      return route.continue();
    });
    await page.addInitScript(ownerKey=>{
      window.AndroidApp={apiKey:()=>ownerKey,haptic(){},updateTelemetry(){},pinWidget(){},openOfficialProtonApp(){},saveApiKey(){}};
    },key);
    await page.goto('https://emas.43-159-63-29.nip.io', {waitUntil:'networkidle'});
    await page.waitForFunction(()=>document.getElementById('vpsState').textContent.includes('VPS tersedia'),null,{timeout:15000});
    assert.equal(await page.locator('#proton strong').innerText(),'Buka aplikasi Proton');
    assert.equal(await page.locator('.version').innerText(),'eMAS Remote 2.1');
    assert.equal(await page.locator('#refreshConnection').innerText(),'Semak sambungan');
    await page.locator('#refreshConnection').click();
    const response=await page.request.get('https://emas.43-159-63-29.nip.io/api/remote/status',{headers:{'X-API-Key':key}});
    assert.equal(response.status(),200);
    const state=await response.json();assert.equal(state.version,'2.1');assert.equal(state.aco_enabled,false);
    const denied=await page.request.get('https://emas.43-159-63-29.nip.io/api/remote/status',{headers:{'X-API-Key':'invalid-test-credential'}});
    assert.equal(denied.status(),401);
    assert.equal(writes.length,0,'Production UI must never POST at startup');
    assert.equal(urls.some(url=>url.includes('/api/status')||url.includes('acotech')),false);
    assert.equal(errors.length,0,errors.join('\n'));
    for(const width of [320,360,390,430]){
      await page.setViewportSize({width,height:844});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    }
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:path.join(release,'remote-v2-mobile.png'),fullPage:true});
    const receipt={version:'2.1',live_vps_ui:true,startup_writes:0,aco_requests:0,console_errors:0,proton_button_present:true,
      authenticated_status:200,invalid_credential:401,aco_enabled:false,mobile_widths:[320,360,390,430],
      phone_installation:'not performed; no phone connected',speaker_playback:'not performed'};
    fs.writeFileSync(path.join(release,'verification.json'),JSON.stringify(receipt,null,2));
    console.log('PASS live HTTPS UI: API works, Proton shortcut present, zero startup POST/ACO calls, no console errors.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
