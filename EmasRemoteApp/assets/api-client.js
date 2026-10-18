(function(root){
  'use strict';
  class RemoteApi {
    constructor(key){this.key=key;this.controllers=new Set();this.writing=false;}
    async request(path,{method='GET',body,timeout=8000,id}={}){
      if(!this.key)throw Error('Masukkan kunci akses dalam Tetapan.');
      const controller=new AbortController();this.controllers.add(controller);
      const timer=setTimeout(()=>controller.abort(),timeout);
      const headers={'X-API-Key':this.key};if(id)headers['Idempotency-Key']=id;
      let payload=body;if(body&&!(body instanceof FormData)){headers['Content-Type']='application/json';payload=JSON.stringify(body);}
      try{
        const response=await fetch(path,{method,body:payload,headers,signal:controller.signal,cache:'no-store'});
        let data;try{data=await response.json();}catch(_){throw Error('Respons pelayan tidak sah (HTTP '+response.status+').');}
        if(!response.ok||!data||data.ok!==true)throw Error(data&&typeof data.detail==='string'?data.detail:'Permintaan gagal (HTTP '+response.status+').');
        return data;
      }catch(error){if(error.name==='AbortError')throw Error('Sambungan terhenti atau melebihi had masa. Semak status sebelum menghantar lagi.');throw error;}
      finally{clearTimeout(timer);this.controllers.delete(controller);}
    }
    async write(path,body,id){
      if(this.writing)throw Error('Permintaan sebelumnya masih diproses.');
      this.writing=true;try{return await this.request(path,{method:'POST',body,id,timeout:20000});}
      finally{this.writing=false;}
    }
    cancel(){for(const controller of this.controllers)controller.abort();}
  }
  root.RemoteApi=RemoteApi;if(typeof module!=='undefined')module.exports=RemoteApi;
})(typeof globalThis!=='undefined'?globalThis:this);
