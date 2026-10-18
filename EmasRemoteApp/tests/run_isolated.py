"""Read code over stdin; run only inside temporary directories, never app state."""
import asyncio
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path

payload = json.load(sys.stdin)
core = types.ModuleType("remote_core")
sys.modules["remote_core"] = core
exec(compile(payload["core"], "remote_core.py", "exec"), core.__dict__)
tests = types.ModuleType("remote_tests")
sys.modules["remote_tests"] = tests
exec(compile(payload["tests"], "test_remote.py", "exec"), tests.__dict__)
result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromModule(tests))
if not result.wasSuccessful(): raise SystemExit(1)

async def integration():
    import httpx
    from fastapi import HTTPException
    with tempfile.TemporaryDirectory(prefix="remote-v2-test-") as folder:
        previous = os.getcwd()
        os.chdir(folder)
        try:
            Path("static/assets").mkdir(parents=True)
            server = types.ModuleType("isolated_server")
            server.__file__ = str(Path(folder) / "server.py")
            source = payload["candidate"].replace('"/app/remote-state.sqlite3"', repr(str(Path(folder) / "state.sqlite")))
            exec(compile(source, server.__file__, "exec"), server.__dict__)
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=server.app), base_url="https://fixture") as client:
                response = await client.get("/api/remote/status")
                assert response.status_code == 401
                headers = {"X-API-Key": server.API_KEY}
                response = await client.get("/api/remote/status", headers=headers)
                assert response.status_code == 200 and response.json()["aco_enabled"] is False
                assert response.json()["telemetry"]["soc_pct"] is None
                response = await client.post("/api/megafon/switch", headers=headers, json={"enabled": False})
                assert response.status_code == 200
                response = await client.post("/api/car/sync", json=[])
                assert response.status_code == 400
                response = await client.post("/api/car/sync", json={"client": "MegaphoneDaemon", "playing": False})
                assert response.status_code == 200 and response.json()["megaphone_switch"] is False
                response = await client.get("/api/remote/status", headers=headers)
                assert response.json()["car"]["online"] is True
                response = await client.post("/api/megafon/upload", files={"file": ("bad.webm", b"bad")})
                assert response.status_code == 401
                try: server.get_aco_client()
                except HTTPException as error: assert error.status_code == 503
                else: raise AssertionError("ACO was not disabled")
            server.REMOTE.db.close()
            print("PASS 9 integrated API/ACO contract checks; no production requests or audio playback")
        finally: os.chdir(previous)

asyncio.run(integration())
