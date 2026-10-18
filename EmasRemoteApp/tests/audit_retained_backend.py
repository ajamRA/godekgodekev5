"""Exercise the retained full backend's previously untested custom audio routes in temp storage."""
import asyncio,io,json,os,socket,sys,tempfile,types,wave
from pathlib import Path
from unittest.mock import patch
import httpx
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/remote-function-audit-20261016'
results=[]

def sound():
    out=io.BytesIO()
    with wave.open(out,'wb') as stream:
        stream.setnchannels(1);stream.setsampwidth(2);stream.setframerate(48000);stream.writeframes(b'\x30\x01'*4800)
    return out.getvalue()

async def main():
    with tempfile.TemporaryDirectory(prefix='remote-retained-custom-') as temp:
        root=Path(temp);(root/'static/assets').mkdir(parents=True)
        source=(ROOT/'artifacts/remote-fix-20261009/server_candidate.py').read_text(encoding='utf-8')
        source=source.replace('"/app/remote-state.sqlite3"',repr(str(root/'state.sqlite')))
        server=types.ModuleType('audit_retained_full');server.__file__=str(root/'server.py')
        previous=Path.cwd();os.chdir(root)
        old_connect=socket.socket.connect
        def deny(*args,**kwargs):raise AssertionError('Unexpected outbound connection')
        socket.socket.connect=deny
        try:
            with patch.dict(os.environ,{'API_KEY':'fixture-key'}):exec(compile(source,server.__file__,'exec'),server.__dict__)
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server.app),base_url='https://fixture',headers={'X-API-Key':'fixture-key'}) as client:
                response=await client.post('/api/megafon/custom/upload',data={'name':'Fixture hon'},files={'file':('fixture.wav',sound(),'audio/wav')})
                item=response.json()['horn'];file=Path(server.CUSTOM_HORN_DIR)/item['file']
                with wave.open(str(file),'rb') as stream:valid=(stream.getnchannels(),stream.getsampwidth(),stream.getframerate())==(1,2,48000)
                results.append({'id':'B01','test':'Real FFmpeg custom horn upload yields PCM16 mono 48kHz','status':'PASS' if response.status_code==200 and valid else 'FAIL'})
                deleted=await client.post('/api/megafon/custom/delete',json={'id':item['id']})
                results.append({'id':'B02','test':'Deleting horn removes metadata and file','status':'PASS' if deleted.status_code==200 and not file.exists() else 'FAIL'})
                with patch.object(server,'time',types.SimpleNamespace(time=lambda:1900000000)):
                    response=await client.post('/api/megafon/custom/upload',data={'name':'Invalid fixture'},files={'file':('broken.mp3',b'not audio','audio/mpeg')})
                results.append({'id':'B03','test':'Invalid horn audio must be rejected','status':'PASS' if response.status_code in (400,415,422) else 'FAIL','http_status':response.status_code})
                with patch.object(server,'time',types.SimpleNamespace(time=lambda:1900000001)):
                    first=await client.post('/api/megafon/custom/upload',data={'name':'Hon one'},files={'file':('first.wav',sound(),'audio/wav')})
                    second=await client.post('/api/megafon/custom/upload',data={'name':'Hon two'},files={'file':('second.wav',sound(),'audio/wav')})
                unique=first.json()['horn']['id']!=second.json()['horn']['id']
                results.append({'id':'B04','test':'Two uploads in same second receive unique immutable IDs','status':'PASS' if unique else 'FAIL'})
                response=await client.post('/api/megafon/presets/save',json={'id':'fixture-custom','tag':'Uji','name':'Ayat ujian'})
                saved=(await client.get('/api/megafon/presets/list')).json()['presets']
                results.append({'id':'B05','test':'Preset save persists a custom tag/name','status':'PASS' if response.status_code==200 and any(x['id']=='fixture-custom' and x['name']=='Ayat ujian' for x in saved) else 'FAIL'})
                response=await client.post('/api/megafon/presets/record',data={'id':'fixture-invalid','name':'Invalid'},files={'file':('broken.webm',b'not audio','audio/webm')})
                results.append({'id':'B06','test':'Invalid custom preset recording must be rejected','status':'PASS' if response.status_code in (400,415,422) else 'FAIL','http_status':response.status_code})
                response=await client.post('/api/megafon/presets/delete',json={'id':'fixture-custom'})
                saved=(await client.get('/api/megafon/presets/list')).json()['presets']
                results.append({'id':'B07','test':'Preset delete removes metadata','status':'PASS' if response.status_code==200 and not any(x['id']=='fixture-custom' for x in saved) else 'FAIL'})
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server.app),base_url='https://fixture') as anonymous:
                    response=await anonymous.post('/api/megafon/presets/save',json={'id':'fixture-anon','name':'Anonymous change'})
                    results.append({'id':'B08','test':'Unauthenticated preset mutation must be rejected','status':'PASS' if response.status_code in (401,403) else 'FAIL','http_status':response.status_code})
        finally:
            if hasattr(server,'REMOTE'):server.REMOTE.db.close()
            socket.socket.connect=old_connect;os.chdir(previous)
    report={'scope':'retained artifacts/remote-fix-20261009/server_candidate.py; production version not confirmed',
            'real_outbound_connections':0,'temporary_storage_only':True,'passed':sum(x['status']=='PASS' for x in results),
            'failed':sum(x['status']=='FAIL' for x in results),'results':results}
    (OUT/'retained-backend.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

asyncio.run(main())
