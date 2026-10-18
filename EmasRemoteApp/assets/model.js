(function(root){
  'use strict';
  function number(value,min,max){return typeof value==='number'&&Number.isFinite(value)&&value>=min&&value<=max?value:null;}
  function telemetry(data,now){
    data=data||{};now=now||Date.now()/1000;
    const updated=number(data.updated_at,1,now+5);
    return {soc:number(data.soc_pct,0,100),range:number(data.range_km,0,2000),updated,
      fresh:data.fresh===true&&updated!==null&&now-updated>=0&&now-updated<15};
  }
  function jobLabel(job){
    const names={queued:'Menunggu IHU',claimed:'Menghantar ke IHU',received:'Diterima IHU · bunyi belum disahkan',
      failed:'Gagal · penerimaan IHU belum disahkan',expired:'Luput · penerimaan IHU belum disahkan',cancelled:'Dibatalkan'};
    return job?names[job.status]||'Status mesej belum tersedia':'Tiada mesej baharu.';
  }
  const exported={number,telemetry,jobLabel};root.RemoteModel=exported;
  if(typeof module!=='undefined')module.exports=exported;
})(typeof globalThis!=='undefined'?globalThis:this);
