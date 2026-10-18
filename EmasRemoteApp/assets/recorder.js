(function(root){
  'use strict';
  const native=()=>root.AndroidApp;
  function sessionId(){const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);return Array.from(bytes,x=>x.toString(16).padStart(2,'0')).join('');}
  class VoiceRecorder {
    constructor(onState,onAudio,onError,media){this.onState=onState;this.onAudio=onAudio;this.onError=onError;this.media=media||navigator.mediaDevices;this.state='idle';this.generation=0;this.timer=null;this.stream=null;this.recorder=null;this.started=0;this.chunks=[];this.session=null;this.nativeMode=false;}
    notify(state){this.state=state;this.onState(state);}
    arm(ms=12000){clearTimeout(this.timer);this.timer=setTimeout(()=>this.finish(),ms);}
    async start(){
      if(this.state!=='idle')return;
      const attempt=++this.generation;
      const bridge=native();
      if(bridge&&bridge.isNativeRecorderSupported&&bridge.isNativeRecorderSupported()){
        if(!bridge.nativeRecorderProtocol||bridge.nativeRecorderProtocol()!==2||!bridge.startRecordingSession){this.onError(Error('Kemas kini APK untuk rakaman bersesi.'));return;}
        if(!bridge.hasMicPermission()){bridge.requestMic();this.onError(VoiceRecorder.describeError({name:'NotAllowedError',message:'Permission belum diberi'}));return;}
        this.nativeMode=true;this.session=sessionId();root.activeVoiceRecorderInstance=this;this.notify('requesting');
        this.timer=setTimeout(()=>{if(attempt===this.generation){this.cancel();this.onError(Error('Mikrofon tidak memberi pengesahan. Cuba semula.'));}},5000);
        try{bridge.startRecordingSession(this.session);}catch(error){this.cancel();this.onError(error);}
        return;
      }
      this.nativeMode=false;
      if(!this.media||typeof MediaRecorder==='undefined'){this.onError(Error('Mikrofon tidak disokong. Kemas kini Android System WebView.'));return;}
      this.notify('requesting');
      try{
        let stream;
        try{stream=await this.media.getUserMedia({audio:{echoCancellation:false,noiseSuppression:false,autoGainControl:false}});}
        catch(error){if(attempt!==this.generation)return;if(error.name==='NotAllowedError')throw error;stream=await this.media.getUserMedia({audio:true});}
        if(attempt!==this.generation){stream.getTracks().forEach(t=>t.stop());return;}
        this.stream=stream;const chunks=[];this.chunks=chunks;
        const mime=['audio/webm;codecs=opus','audio/webm','audio/mp4'].find(type=>MediaRecorder.isTypeSupported(type));
        const recorder=new MediaRecorder(stream,mime?{mimeType:mime}:{});this.recorder=recorder;
        recorder.ondataavailable=e=>{if(attempt===this.generation&&e.data&&e.data.size)chunks.push(e.data);};
        recorder.onerror=()=>{if(attempt===this.generation){this.cancel();this.onError(Error('Rakaman mikrofon gagal.'));}};
        recorder.onstop=()=>{
          if(this.stream===stream)this.stopTracks();
          if(attempt!==this.generation)return;
          this.recorder=null;this.chunks=[];
          const blob=new Blob(chunks,{type:recorder.mimeType||mime||'audio/webm'});
          this.upload(blob,attempt);
        };
        recorder.start(500);this.started=Date.now();this.notify('recording');this.arm();
      }catch(error){if(attempt===this.generation){this.cancel();this.onError(VoiceRecorder.describeError(error));}}
    }
    upload(blob,attempt){
      clearTimeout(this.timer);this.timer=null;
      if(!blob.size||blob.size>8*1024*1024){this.notify('idle');this.onError(Error('Rakaman kosong atau terlalu besar.'));return;}
      this.notify('uploading');
      Promise.resolve().then(()=>this.onAudio(blob)).catch(error=>this.onError(error)).finally(()=>{if(attempt===this.generation)this.notify('idle');});
    }
    finish(){
      if(this.state==='requesting'){this.cancel();return;}
      if(this.state!=='recording')return;
      clearTimeout(this.timer);this.timer=null;
      if(Date.now()-this.started<350){this.cancel();return;}
      this.notify('stopping');
      if(this.nativeMode){
        const id=this.session,attempt=this.generation;
        this.timer=setTimeout(()=>{if(attempt===this.generation){this.cancel();this.onError(Error('Keputusan rakaman belum diterima. Rakaman tidak dihantar.'));}},5000);
        try{native().stopRecordingSession(id);}catch(error){this.cancel();this.onError(error);}return;
      }
      try{this.recorder.stop();this.stopTracks();}catch(_){this.cancel();this.onError(Error('Rakaman terhenti.'));}
    }
    stopTracks(){if(this.stream)this.stream.getTracks().forEach(t=>t.stop());this.stream=null;}
    cancel(){
      if(this.state==='uploading')return;
      ++this.generation;clearTimeout(this.timer);this.timer=null;
      const id=this.session;this.session=null;
      if(root.activeVoiceRecorderInstance===this)root.activeVoiceRecorderInstance=null;
      if(id&&native()&&native().cancelRecordingSession){try{native().cancelRecordingSession(id);}catch(_){}}
      const recorder=this.recorder;this.recorder=null;this.chunks=[];
      if(recorder&&recorder.state!=='inactive'){try{recorder.stop();}catch(_){}}
      this.stopTracks();this.notify('idle');
    }
    static describeError(error){const name=error.name||'Error';const hints={NotAllowedError:'Benarkan mikrofon dalam tetapan eMAS Remote.',NotReadableError:'Tutup aplikasi rakaman atau panggilan lain.',NotFoundError:'Tiada mikrofon tersedia.',AbortError:'Pembukaan mikrofon terhenti.'};const result=Error((hints[name]||'Rakaman tidak dapat dimulakan.')+' ['+name+': '+(error.message||'tiada butiran')+']');result.name=name;return result;}
  }
  root.onNativeRecordingStarted=function(id){const vr=root.activeVoiceRecorderInstance;if(!vr||vr.session!==id||vr.state!=='requesting')return;vr.started=Date.now();vr.notify('recording');vr.arm();};
  root.onNativeAudioRecorded=function(base64,mime,id){
    const vr=root.activeVoiceRecorderInstance;
    if(!vr||!id||vr.session!==id||!['recording','stopping'].includes(vr.state))return;
    // Consume ownership before parsing or dispatching: stale/duplicate callbacks cannot send audio.
    root.activeVoiceRecorderInstance=null;vr.session=null;clearTimeout(vr.timer);vr.timer=null;
    try{
      if(typeof base64!=='string'||base64.length>12*1024*1024)throw Error('Rakaman terlalu besar.');
      const binary=atob(base64),bytes=new Uint8Array(binary.length);
      for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);
      vr.upload(new Blob([bytes],{type:mime||'audio/mp4'}),vr.generation);
    }catch(error){vr.notify('idle');vr.onError(error);}
  };
  root.onNativeAudioError=function(message,id){const vr=root.activeVoiceRecorderInstance;if(!vr||!id||vr.session!==id)return;vr.cancel();vr.onError(Error(message||'Rakaman gagal.'));};
  root.VoiceRecorder=VoiceRecorder;
})(typeof globalThis!=='undefined'?globalThis:this);
