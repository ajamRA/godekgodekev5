const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(require('node:path').join(__dirname,'../assets/app.js'),'utf8');
const start=source.indexOf('  function updateActiveBadge()');
const end=source.indexOf('  // ==================== CUSTOM HORN',start);
assert.ok(start>0&&end>start);
class Element {
  constructor(){this.children=[];this.textContent='';}
  append(...items){this.children.push(...items);}
  replaceChildren(){this.children=[];}
  setAttribute(){}
}
const nodes=new Map();const $=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);};
let state={ok:true,active:'animewow',sync_status:'awaiting_car',sounds:{animewow:{name:'Anime Wow'}}};
let sent=[];let toast='';
const context={console,document:{createElement:()=>new Element()},localStorage:{getItem:()=>null},
  fetch:async()=>({json:async()=>state}),$: $,apiKey:'fixture',lockSounds:{},activeLockKey:'mario',
  lockSyncStatus:'awaiting_car',currentPreviewAudio:null,window:{},Audio:class{},
  guarded:async(k,fn)=>fn(),postJson:async(url,body)=>sent.push({url,body}),toast:message=>toast=message,
  labelButton:(cls,label,handler)=>({className:cls,textContent:label,onclick:handler}),confirm:()=>false};
vm.createContext(context);vm.runInContext(source.slice(start,end),context);
(async()=>{
  await vm.runInContext('loadLockSounds()',context);
  assert.match($('activeSoundBadge').textContent,/Pilihan server: Anime Wow.*Menunggu kereta/);
  assert.equal(sent.length,0,'initial status is read-only');
  await context.window.applyLockSound('guru','Guru');
  assert.deepEqual(JSON.parse(JSON.stringify(sent)),[{url:'/api/locksound/set',body:{sound:'guru'}}]);
  assert.match(toast,/Pilihan disimpan di server/);assert.doesNotMatch(toast,/berjaya aktif di kereta/);
  assert.match($('activeSoundBadge').textContent,/Menunggu kereta/);
  state={...state,active:'guru',sync_status:'file_synced',sounds:{guru:{name:'Guru'}}};
  await vm.runInContext('loadLockSounds()',context);
  assert.match($('activeSoundBadge').textContent,/Pilihan server: Guru.*Fail kereta sepadan/);
  assert.doesNotMatch($('activeSoundBadge').textContent,/bunyi disahkan|dimainkan/i);
  console.log('PASS phone UI: read-only startup, offline selection, waiting badge, confirmed-file badge, no acoustic success claim');
})().catch(error=>{console.error(error);process.exitCode=1;});
