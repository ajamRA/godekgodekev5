"""Audit current local server in temp storage with socket connections prohibited."""
import asyncio,json,os,socket,sys,tempfile,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'EmasRemoteApp/server'))
OUT=ROOT/'artifacts/remote-function-audit-20261016'
import httpx

async def main():
    source=(ROOT/'EmasRemoteApp/server/server_candidate.py').read_text(encoding='utf-8')
    results=[]
    with tempfile.TemporaryDirectory(prefix='remote-complete-contract-') as temp:
        root=Path(temp);(root/'static/assets').mkdir(parents=True)
        source=source.replace('"/app/remote-state.sqlite3"',repr(str(root/'state.sqlite')))
        source=source.replace('"/app/avas-state.sqlite3"',repr(str(root/'avas.sqlite')))
        source=source.replace('"/app/avas-audio"',repr(str(root/'avas-audio')))
        server=types.ModuleType('audit_local_server');server.__file__=str(root/'server.py')
        before=Path.cwd();old_connect=socket.socket.connect;old_env=os.environ.copy()
        def deny(*args,**kwargs):raise AssertionError('Unexpected outbound socket in isolated audit')
        socket.socket.connect=deny
        os.environ.update(API_KEY='fixture-key',REMOTE_AVAS_DEVICE_ID='fixture-ihu',
                          REMOTE_AVAS_AUDIO_DIR=str(root/'avas-audio'),REMOTE_AVAS_DATABASE=str(root/'avas.sqlite'))
        os.chdir(root)
        try:
            exec(compile(source,server.__file__,'exec'),server.__dict__)
            routes={r.path for r in server.app.routes if hasattr(r,'path')}
            expected=['/api/remote/status','/api/locksound/list','/api/locksound/set','/api/locksound/upload','/api/locksound/delete',
                      '/api/megafon/switch','/api/megafon/speak','/api/megafon/upload',
                      '/api/megafon/custom/list','/api/megafon/custom/upload','/api/megafon/custom/delete',
                      '/api/megafon/presets/list','/api/megafon/presets/save',
                      '/api/remote/avas/state','/api/remote/avas/upload','/api/remote/avas/apply','/api/remote/avas/restore']
            for path in expected:results.append({'test':'UI route exists','path':path,'status':'PASS' if path in routes else 'FAIL'})
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server.app),base_url='https://fixture') as client:
                for path in ['/api/remote/status','/api/locksound/list','/api/megafon/status','/api/remote/avas/state']:
                    response=await client.get(path)
                    results.append({'test':'Unauthenticated read is rejected','path':path,'http_status':response.status_code,
                                    'status':'PASS' if response.status_code in (401,403) else 'FAIL'})
                headers={'X-API-Key':'fixture-key'}
                for path in ['/api/remote/status','/api/locksound/list','/api/remote/avas/state']:
                    response=await client.get(path,headers=headers)
                    results.append({'test':'Authenticated read works','path':path,'http_status':response.status_code,
                                    'status':'PASS' if response.status_code==200 else 'FAIL'})
                response=await client.post('/api/megafon/switch',headers=headers,json={'enabled':True,'duration_min':10})
                results.append({'test':'Enable isolated megaphone','status':'PASS' if response.status_code==200 else 'FAIL'})
                response=await client.post('/api/megafon/switch',headers=headers,json={'enabled':False})
                results.append({'test':'Backend can turn megaphone OFF','status':'PASS' if response.status_code==200 and server.REMOTE.switch()['enabled'] is False else 'FAIL'})
                server.save_lock_state({'active':'original','pending_deploy':None,'sounds':{'fixture':{'name':'Fixture','file':'fixture.wav'}}})
                response=await client.post('/api/locksound/set',json={'sound':'fixture'})
                results.append({'test':'Unauthenticated lock write is rejected','path':'/api/locksound/set','http_status':response.status_code,
                                'status':'PASS' if response.status_code in (401,403) else 'FAIL'})
        finally:
            for name in ['REMOTE','AVAS_REMOTE']:
                obj=getattr(server,name,None)
                if obj is not None:obj.db.close()
            os.chdir(before);socket.socket.connect=old_connect;os.environ.clear();os.environ.update(old_env)
    report={'scope':'current repository server_candidate.py only; production state not inferred',
            'storage':'temporary directory only','real_outbound_connections':0,'results':results,
            'passed':sum(x['status']=='PASS' for x in results),'failed':sum(x['status']=='FAIL' for x in results)}
    (OUT/'backend-contract.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

asyncio.run(main())
