const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const assets=path.resolve(__dirname,'../assets');
(async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage({viewport:{width:390,height:844}});
    const writes=[];
    let currentJob=null,dropNextApply=false;
    const upload={upload_id:'a'.repeat(32),name:'pixel.wav',bytes:581788,sha256:'b'.repeat(64),duration_seconds:5.979687};
    await page.addInitScript(()=>localStorage.setItem('emas_api_key','fixture'));
    await page.addInitScript(()=>{
      const original=window.fetch;
      window.__avasUploads=[];
      window.fetch=async function(input,options){
        if(typeof input==='string' && input.endsWith('/avas/upload') && options && options.body instanceof FormData){
          const file=options.body.get('file'),buffer=await file.arrayBuffer(),v=new DataView(buffer);
          const info=window.AvasWav.inspect(buffer);
          if(v.getUint16(22,true)!==1 || v.getUint32(24,true)!==48000 || v.getUint16(34,true)!==16)throw Error('Wrong WAV format');
          window.__avasLastUpload=file;
          window.__avasUploads.push(info);
        }
        return original.call(this,input,options);
      };
    });
    const latestUpload=()=>page.evaluate(()=>window.__avasUploads.at(-1));
    async function saveUpload(name){
      if(!process.env.AVAS_TEST_OUTPUT)return;
      const base64=await page.evaluate(async()=>{
        const bytes=new Uint8Array(await window.__avasLastUpload.arrayBuffer());let binary='';
        for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
        return btoa(binary);
      });
      fs.writeFileSync(path.join(process.env.AVAS_TEST_OUTPUT,name),Buffer.from(base64,'base64'));
    }
    await page.route('**/*',async route=>{
      const req=route.request(),url=new URL(req.url());
      if(req.method()==='POST')writes.push(url.pathname);
      const name=path.basename(url.pathname);
      if(url.pathname==='/'||url.pathname==='/index.html')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(assets,'index.html'),'utf8')});
      if((url.pathname.startsWith('/static/remote-v2/')||url.pathname==='/'+name)&&fs.existsSync(path.join(assets,name)))return route.fulfill({contentType:name.endsWith('.css')?'text/css':name.endsWith('.html')?'text/html':'application/javascript',body:fs.readFileSync(path.join(assets,name))});
      const send=data=>route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
      if(url.pathname==='/api/remote/avas/state')return send({ok:true,enabled:true,device_online:true,ready:true,device_config:{active_slot:3},job:currentJob});
      if(url.pathname==='/api/remote/avas/upload')return send({ok:true,upload});
      if(['/api/remote/avas/apply','/api/remote/avas/restore'].includes(url.pathname)){
        const body=req.postDataJSON();
        currentJob={id:'c'.repeat(32),request_id:body.request_id,slot:body.slot,status:'applied'};
        if(dropNextApply){dropNextApply=false;currentJob.status='dispatching';return route.abort();}
        return send({ok:true,job:currentJob});
      }
      if(url.pathname==='/api/remote/status')return send({ok:true,car:{online:false}});
      if(url.pathname.includes('locksound'))return send({ok:true,sounds:[],presets:[]});
      return send({ok:true,horns:[],presets:[]});
    });
    await page.goto('https://fixture.test/');
    await page.waitForFunction(()=>document.getElementById('avasConnection').textContent==='IHU AVAS bersambung');
    assert.equal(writes.length,0,'page load must be read-only');
    for(const width of [320,360,390,430]){
      await page.setViewportSize({width,height:844});
      const over=await page.locator('section[aria-labelledby="avasTitle"]').evaluate(el=>el.scrollWidth>el.clientWidth+1);
      assert.equal(over,false,'AVAS card must fit '+width);
    }
    const wav=path.resolve(__dirname,'../../artifacts/avas-research-20261015/vendor/etc/avas/sound_type_3/pixel.wav');
    await page.locator('#avasFile').setInputFiles(wav);
    await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
    assert.equal(writes.length,0,'choosing a WAV must not change server or IHU');
    assert.equal(await page.locator('#avasPreview').isVisible(),true);
    await page.locator('#avasUpload').click();
    await page.waitForFunction(()=>!document.getElementById('avasApply').disabled);
    assert.deepEqual(writes,['/api/remote/avas/upload'],'upload must not apply');
    page.on('dialog',dialog=>dialog.accept());
    await page.locator('#avasApply').click();
    await page.waitForFunction(()=>document.getElementById('avasJob').textContent.includes('fizikal belum disahkan'));
    assert.deepEqual(writes,['/api/remote/avas/upload','/api/remote/avas/apply']);
    const out=process.env.AVAS_TEST_OUTPUT;
    if(out){fs.mkdirSync(out,{recursive:true});await page.locator('section[aria-labelledby="avasTitle"]').screenshot({path:path.join(out,'remote-avas-preview.png')});}
    await page.locator('#avasSlot').selectOption('2');
    await page.locator('#avasRestore').click();
    await page.waitForFunction(()=>!document.getElementById('avasRestore').disabled);
    assert.equal(currentJob.slot,2);
    assert.equal(writes.at(-1),'/api/remote/avas/restore');
    dropNextApply=true;
    await page.locator('#avasApply').click();
    await page.waitForFunction(()=>document.getElementById('avasJob').textContent.includes('Menunggu keputusan'));
    const before=writes.length;
    await page.locator('#avasRefresh').click();await page.locator('#avasRefresh').click();
    assert.equal(writes.length,before,'refresh must not retry an uncertain write');
    assert.equal(await page.locator('#avasApply').isDisabled(),true);
    if(process.env.AVAS_CONVERT_FIXTURES){
      const fixtures=process.env.AVAS_CONVERT_FIXTURES;
      const beforeConvert=writes.length;
      await page.locator('#avasFile').setInputFiles(path.join(fixtures,'song.mp3'));
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      assert.equal(writes.length,beforeConvert,'conversion stays on the phone');
      assert.match(await page.locator('#avasMessage').textContent(),/48 kHz.*mono.*16-bit/);
      await page.locator('#avasUpload').click();
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      const mp3=await latestUpload();
      assert.ok(mp3.duration_seconds>7.9 && mp3.duration_seconds<=8.1);
      await page.locator('#avasStart').fill('3');
      await page.locator('#avasDuration').fill('2');
      assert.equal(await page.locator('#avasUpload').isDisabled(),true,'editing a clip invalidates old WAV');
      assert.equal(await page.locator('#avasPreview').isVisible(),false);
      const beforePrepare=writes.length;
      await page.locator('#avasPrepare').click();
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      assert.equal(writes.length,beforePrepare,'preparing a clip does not upload');
      assert.match(await page.locator('#avasMessage').textContent(),/mula 3.00/);
      await page.locator('#avasUpload').click();
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      assert.equal((await latestUpload()).bytes,44+2*96000);
      await saveUpload('converted-mp3-trim.wav');
      await page.locator('#avasDuration').fill('16');
      await page.locator('#avasPrepare').click();
      await page.waitForFunction(()=>document.getElementById('avasMessage').classList.contains('avas-error'));
      assert.equal(await page.locator('#avasUpload').isDisabled(),true);
      await page.locator('#avasFile').setInputFiles(path.join(fixtures,'song.m4a'));
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      await page.locator('#avasUpload').click();
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      assert.ok((await latestUpload()).duration_seconds>=3.9);
      await saveUpload('converted-m4a.wav');
      const readyWav=path.join(fixtures,'ready-long.wav');
      if(fs.existsSync(readyWav)){
        await page.locator('#avasFile').setInputFiles(readyWav);
        await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
        assert.equal(await page.locator('#avasDuration').inputValue(),'15');
        await page.locator('#avasUpload').click();
        await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
        assert.equal((await latestUpload()).duration_seconds,15,'valid 48k mono WAV over 15s must also be clipped');
      }
      await page.locator('#avasFile').setInputFiles(path.join(fixtures,'long.wav'));
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      assert.equal(await page.locator('#avasDuration').inputValue(),'15');
      await page.locator('#avasUpload').click();
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      assert.equal((await latestUpload()).bytes,1440044);
      await saveUpload('converted-15s.wav');
      await page.locator('#avasDuration').fill('13');
      await page.locator('#avasPrepare').click();
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      await page.locator('#avasUpload').click();
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      assert.equal((await latestUpload()).duration_seconds,13);
      await saveUpload('converted-13s.wav');
      for(const width of [320,360,390,430]){
        await page.setViewportSize({width,height:844});
        assert.equal(await page.locator('#avasClip').evaluate(el=>el.scrollWidth>el.clientWidth+1),false,'clip controls fit '+width);
      }
      if(out)await page.locator('section[aria-labelledby="avasTitle"]').screenshot({path:path.join(out,'remote-audio-convert.png')});
      const beforeBad=writes.length;
      await page.locator('#avasFile').setInputFiles({name:'invalid.mp3',mimeType:'audio/mpeg',buffer:Buffer.from('broken')});
      await page.waitForFunction(()=>document.getElementById('avasMessage').classList.contains('avas-error'));
      assert.equal(await page.locator('#avasUpload').isDisabled(),true);
      assert.equal(await page.locator('#avasPreview').isVisible(),false);
      assert.equal(writes.length,beforeBad);
      await page.evaluate(()=>{
        const decode=window.AvasAudio.decode;window.__savedDecode=decode;
        window.AvasAudio.decode=async buffer=>{
          await new Promise(resolve=>window.__releaseDecode=resolve);
          const result=await decode(buffer);window.__oldDecodeFinished=true;return result;
        };
      });
      await page.locator('#avasFile').setInputFiles(path.join(fixtures,'song.mp3'));
      await page.waitForFunction(()=>typeof window.__releaseDecode==='function');
      await page.locator('#avasFile').setInputFiles(wav);
      await page.waitForFunction(()=>!document.getElementById('avasUpload').disabled);
      await page.evaluate(()=>window.__releaseDecode());
      await page.waitForFunction(()=>window.__oldDecodeFinished===true);
      assert.match(await page.locator('#avasMessage').textContent(),/pixel.wav/,'old decode must not replace a newer selection');
      await page.evaluate(()=>window.AvasAudio.decode=window.__savedDecode);
      assert.equal(writes.length,beforeBad,'file replacement and delayed decoding remain local');
      console.log('PASS actual Web Audio: MP3/M4A decode, PCM16 mono 48k, 13s selection, 15s maximum, invalid audio rejection, four widths, and no automatic upload/apply.');
    }
    await page.setViewportSize({width:390,height:844});
    console.log('PASS AVAS browser: read-only startup, four phone widths, local WAV preview, separate upload/apply, restore slot, and no automatic retry after lost response.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
