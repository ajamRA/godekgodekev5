const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),assets=path.join(root,'EmasRemoteApp/assets');
(async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage({viewport:{width:390,height:1000},reducedMotion:'reduce'});
    const writes=[],errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(()=>localStorage.setItem('emas_api_key','fixture'));
    await page.route('**/*',route=>{
      const req=route.request(),url=new URL(req.url()),name=path.basename(url.pathname);
      if(req.method()!=='GET')writes.push(url.pathname);
      if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(assets,'index.html'))});
      if(fs.existsSync(path.join(assets,name))&&fs.statSync(path.join(assets,name)).isFile())return route.fulfill({contentType:name.endsWith('.css')?'text/css':'application/javascript',body:fs.readFileSync(path.join(assets,name))});
      const send=data=>route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
      if(url.pathname.endsWith('/avas/state'))return send({ok:true,enabled:false,ready:false,device_config:null,job:null});
      if(url.pathname.includes('locksound'))return send({ok:true,sounds:{},active:'original'});
      return send({ok:true,car:{online:false},horns:[],presets:[]});
    });
    await page.goto('https://fixture.test/');
    await page.waitForFunction(()=>document.getElementById('avasConnection').textContent.includes('belum disediakan'));
    assert.deepEqual(errors,[],'no JavaScript bootstrap errors');assert.deepEqual(writes,[]);
    for(const width of [320,360,390,430,980]){
      await page.setViewportSize({width,height:1000});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'no overflow at '+width);
      const mic=await page.locator('#micBtn').boundingBox();assert.ok(mic.height>=90 && mic.width>=200,'microphone remains an obvious touch target');
      assert.equal(await page.locator('body').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(247, 246, 243)');
    }
    await page.setViewportSize({width:390,height:1000});
    await page.locator('#settingsToggle').click();
    assert.equal(await page.locator('#settingsModal').isVisible(),true);
    const modal=await page.locator('#settingsModal .modal-window').boundingBox();assert.ok(modal.width<=350);
    await page.locator('#settingsModal .btn-modal-close').click();
    await page.locator('.sound-library summary').click();
    assert.ok(await page.locator('#lockGrid .sound-card').count()>=1);
    await page.locator('.sound-library summary').click();
    assert.equal(await page.locator('#debugBody').isVisible(),false);
    await page.locator('#btnToggleDebug').click();assert.equal(await page.locator('#debugBody').isVisible(),true);
    await page.locator('#btnToggleDebug').click();
    await page.locator('.section-nav a[href="#avas"]').click();
    assert.equal(new URL(page.url()).hash,'#avas');
    await page.evaluate(()=>scrollTo(0,0));
    await page.screenshot({path:path.join(root,'artifacts/remote-minimal-20261016/checks/minimal-phone.png'),fullPage:false});
    await page.setViewportSize({width:1100,height:1000});await page.evaluate(()=>scrollTo(0,0));
    await page.screenshot({path:path.join(root,'artifacts/remote-minimal-20261016/checks/minimal-desktop.png'),fullPage:false});
    assert.deepEqual(writes,[],'navigation, settings and expansion do not send commands');
    console.log('PASS minimalist UI: five widths, microphone target, light palette, settings modal, sound library, diagnostics toggle, section navigation, zero bootstrap errors and zero command writes.');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
