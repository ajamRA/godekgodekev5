"""Exercise the actual server candidate with private temporary state, without lifespan/network."""
import asyncio
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import types
import httpx

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / 'EmasRemoteApp/server'
sys.path.insert(0, str(SERVER))

async def main():
    with tempfile.TemporaryDirectory(prefix='avas-candidate-') as folder:
        temp = Path(folder)
        previous = Path.cwd()
        os.chdir(temp)
        try:
            (temp / 'static/assets').mkdir(parents=True)
            os.environ['REMOTE_AVAS_DEVICE_ID'] = 'fixture-ihu'
            os.environ['REMOTE_AVAS_DATABASE'] = str(temp / 'avas.sqlite3')
            os.environ['REMOTE_AVAS_AUDIO_DIR'] = str(temp / 'avas-audio')
            source = (SERVER / 'server_candidate.py').read_text(encoding='utf-8')
            source = source.replace('"/app/remote-state.sqlite3"', repr(str(temp / 'remote.sqlite3')))
            candidate = types.ModuleType('isolated_avas_candidate')
            candidate.__file__ = str(temp / 'server.py')
            exec(compile(source, candidate.__file__, 'exec'), candidate.__dict__)
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=candidate.app), base_url='https://fixture') as client:
                assert (await client.get('/api/remote/avas/state')).status_code == 401
                headers = {'X-API-Key': candidate.API_KEY}
                wave = (ROOT / 'artifacts/avas-research-20261015/vendor/etc/avas/sound_type_3/pixel.wav').read_bytes()
                response = await client.post('/api/remote/avas/upload', headers=headers, files={'file': ('pixel.wav', wave, 'audio/wav')})
                assert response.status_code == 200, response.text
                upload = response.json()['upload']
                assert upload['sha256'] == hashlib.sha256(wave).hexdigest()
                apply = {'slot': 3, 'upload_id': upload['upload_id'], 'request_id': 'candidate-test-1'}
                assert (await client.post('/api/remote/avas/apply', headers=headers, json=apply)).status_code == 409
                sync = {'device_id': 'fixture-ihu', 'bridge_protocol': 1, 'daemon_protocol': 2,
                        'audio_revision': '20261014', 'status': {'ok': True, 'is_android': True,
                        'protocol_version': 2, 'audio_revision': '20261014', 'active_slot': 3}}
                assert (await client.post('/api/remote/avas/sync', headers=headers, json=sync)).status_code == 200
                queued = (await client.post('/api/remote/avas/apply', headers=headers, json=apply)).json()['job']
                job = (await client.post('/api/remote/avas/sync', headers=headers, json=sync)).json()['job']
                assert job['id'] == queued['id'] and job['sha256'] == upload['sha256']
                download = await client.get(job['audio_path'], headers=headers)
                assert download.content == wave
                assert (await client.post('/api/remote/avas/sync', headers=headers, json=sync)).json()['job'] is None
                sync['result'] = {'id': job['id'], 'status': 'applied'}
                assert (await client.post('/api/remote/avas/sync', headers=headers, json=sync)).status_code == 200
                assert (await client.get('/api/remote/avas/state', headers=headers)).json()['job']['status'] == 'applied'
                response = await client.get('/api/remote/status', headers=headers)
                assert response.status_code == 200 and response.json()['aco_enabled'] is False
            candidate.REMOTE.db.close()
            candidate.AVAS_REMOTE.db.close()
            print('PASS integrated candidate: auth, unchanged original WAV, offline rejection, bridge handshake, single claim, authenticated download, result ACK, existing remote status and ACO disabled. Network/vehicle operations=0.')
        finally:
            os.chdir(previous)

asyncio.run(main())
