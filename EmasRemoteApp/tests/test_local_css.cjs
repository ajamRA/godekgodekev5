const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'../..');
(async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    for(const relative of ['EmasRemoteApp/assets/index.html','artifacts/remote-minimal-20261016/preview/Remote_Pilot_Preview.html']){
      const page=await browser.newPage({viewport:{width:980,height:1000},reducedMotion:'reduce'});
      const network=[];
      await page.route(/^https?:/,route=>{network.push(route.request().url());return route.abort();});
      await page.goto(pathToFileURL(path.join(root,relative)).href);
      const style=await page.locator('body').evaluate(el=>({color:getComputedStyle(el).color,background:getComputedStyle(el).backgroundColor,font:getComputedStyle(el).fontFamily}));
      assert.notEqual(style.background,'rgba(0, 0, 0, 0)','CSS must load when opening '+relative);
      assert.notEqual(style.color,'rgb(0, 0, 0)','must not render unstyled default black text');
      assert.equal(await page.locator('#avasDuration').getAttribute('max'),'15');
      if(relative.includes('Preview')){
        await page.locator('#avasFile').setInputFiles(path.join(root,'artifacts/remote-convert-15s-20261016/fixtures/song.mp3'));
        await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
        assert.match(await page.locator('#avasMessage').textContent(),/48 kHz.*mono.*16-bit/);
        await page.screenshot({path:path.join(root,'artifacts/remote-minimal-20261016/checks/preview-desktop.png'),fullPage:false});
        assert.equal(network.length,0,'standalone preview must not contact a server');
      }
      await page.close();
    }
    console.log('PASS direct local HTML and standalone preview: CSS loaded, 15s limit, local MP3 conversion, and zero preview network requests.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
