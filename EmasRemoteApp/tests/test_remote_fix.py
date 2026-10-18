"""Focused regression tests; fixtures only, no production requests or playback."""
import asyncio
import ast
import importlib.util
import io
import sqlite3
import sys
import tempfile
import types
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "artifacts/remote-fix-20261009/test-deps"))
sys.path.insert(0, str(ROOT / "EmasRemoteApp/server"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from fastapi import HTTPException, UploadFile
from remote_core import RemoteController
from test_remote import RemoteTests, FakeRequest


class RemoteFixTests(RemoteTests):
    async def test_two_messages_remain_in_fifo_order(self):
        await self.enable()
        first = await self.speak("alihkan")
        second = await self.speak("salam")
        self.assertEqual(self.controller.sync({"client": "MegaphoneDaemon"})["id"], first["id"])
        self.assertIsNone(self.controller.sync({"client": "MegaphoneDaemon"}))
        self.clock += 3
        self.assertEqual(self.controller.sync({"client": "MegaphoneDaemon"})["id"], first["id"])
        sent = self.controller.sync({"client": "MegaphoneDaemon", "megaphone_ack": first["id"]})
        self.assertEqual(sent["id"], second["id"])
        self.assertEqual(next(j for j in self.controller.status()["jobs"] if j["id"] == first["id"])["status"], "received")

    async def test_switch_can_activate_offline_without_claiming_car_awake(self):
        result = await self.controller.set_switch(FakeRequest({"enabled": True}))
        self.assertTrue(result["enabled"])
        self.assertFalse(self.controller.online())
        with self.assertRaises(HTTPException) as caught:
            await self.speak()
        self.assertEqual(caught.exception.status_code, 503)

    async def test_retry_budget_is_bounded_and_no_later_audio(self):
        await self.enable()
        job = await self.speak()
        for _ in range(self.controller.MAX_DISPATCHES):
            sent = self.controller.sync({"client": "MegaphoneDaemon"})
            self.assertEqual(sent["id"], job["id"])
            self.assertIsNone(self.controller.sync({"client": "MegaphoneDaemon"}))
            self.clock += 3
        self.assertIsNone(self.controller.sync({"client": "MegaphoneDaemon"}))
        state = self.controller.status()["jobs"][0]
        self.assertEqual((state["status"], state["error"], state["dispatch_count"]), ("failed", "ack_timeout", 5))
        self.assertIsNone(self.controller.sync({"client": "MegaphoneDaemon", "megaphone_ack": job["id"]}))
        self.assertEqual(self.controller.status()["jobs"][0]["status"], "failed")

    async def test_diagnostic_failure_not_misreported_as_playback(self):
        await self.enable()
        job = await self.speak()
        self.controller.sync({"client": "MegaphoneDaemon"})
        self.controller.sync({"client": "MegaphoneDaemon", "diagnostic_events": [
            "1001 FAIL source=cloud:" + job["id"] + " reason=download_failed"]})
        state = self.controller.status()["jobs"][0]
        self.assertEqual((state["status"], state["error"]), ("failed", "download_failed"))
        self.assertFalse(self.controller.status()["megaphone"]["playback_confirmation_supported"])

    async def test_custom_preset_and_horn_still_resolve(self):
        await self.enable()
        self.controller.preset_provider = lambda: [{"id": "own_voice", "key": "own_voice", "name": "Suara Saya",
            "has_custom_audio": True, "audio_url": "https://owner.test/static/megafon/salam.wav?t=1"}]
        self.controller.horn_provider = lambda: [{"name": "Hon Saya", "audio_url": "/static/megafon/tepi.wav"}]
        custom = await self.controller.speak(FakeRequest({"preset": "own_voice"}))
        horn = await self.controller.speak(FakeRequest({"preset": "horn"}))
        jobs = {j["id"]: j for j in self.controller.status()["jobs"]}
        self.assertEqual(jobs[custom["id"]]["text"], "Suara Saya")
        self.assertEqual(jobs[horn["id"]]["text"], "Hon Saya")
        self.assertEqual((self.path / (horn["id"] + ".wav")).read_bytes(), (self.path / "tepi.wav").read_bytes())
        ids = [p["id"] for p in self.controller.available_presets()]
        self.assertIn("own_voice", ids)
        self.assertIn("horn", ids)

    async def test_wrong_default_terima_kasih_never_falls_back_to_datang(self):
        await self.enable()
        self.controller.preset_provider = lambda: [{"id": "preset_terima_kasih", "key": "terima_kasih",
            "name": "Terima kasih", "has_custom_audio": False, "audio_url": "/static/megafon/tepi.wav"}]
        for preset in ["terima_kasih", "preset_terima_kasih"]:
            with self.assertRaises(HTTPException) as caught:
                await self.controller.speak(FakeRequest({"preset": preset}))
            self.assertEqual(caught.exception.status_code, 404)
        self.assertEqual(self.controller.status()["jobs"], [])

    async def test_builtin_custom_override_is_preserved(self):
        await self.enable()
        self.controller.preset_provider = lambda: [{"id": "preset_terima_kasih", "key": "terima_kasih", "name": "Suara Baru",
            "has_custom_audio": True, "audio_url": "/static/megafon/salam.wav"}]
        result = await self.controller.speak(FakeRequest({"preset": "terima_kasih"}))
        self.assertEqual(self.controller.status()["jobs"][0]["id"], result["id"])

    async def test_external_traversal_invalid_and_empty_audio_rejected(self):
        await self.enable()
        for url in ["https://evil.test/static/megafon/salam.wav", "/static/megafon/../../server.py",
                    "/static/megafon/%2e%2e/%2e%2e/server.wav", "file:///static/megafon/salam.wav", "https:/static/megafon/salam.wav",
                    "data:/static/megafon/salam.wav", "https://[broken/static/megafon/salam.wav",
                    "/static/locksounds/test.wav", "/static/megafon/zero.wav"]:
            with self.assertRaises(HTTPException):
                await self.controller.speak(FakeRequest({"audio_url": url}))
        self.assertEqual(self.controller.status()["jobs"], [])

    async def test_legacy_explicit_custom_horn_url_does_not_apply_speech_filter(self):
        await self.enable()
        (self.path / "custom").mkdir()
        raw = (self.path / "tepi.wav").read_bytes()
        (self.path / "custom/horn.wav").write_bytes(raw)
        result = await self.controller.speak(FakeRequest({"audio_url": "/static/megafon/custom/horn.wav", "text": "Hon"}))
        self.assertEqual((self.path / (result["id"] + ".wav")).read_bytes(), raw)

    async def test_no_preset_horn_or_text_silent_fallback(self):
        await self.enable()
        for data in [{"preset": "horn"}, {"text": "This is not TTS"}, {}, {"preset": []}]:
            with self.assertRaises(HTTPException):
                await self.controller.speak(FakeRequest(data))
        self.assertEqual(self.controller.status()["jobs"], [])

    async def test_snapshot_survives_preset_edit(self):
        await self.enable()
        result = await self.speak()
        snapshot = self.path / (result["id"] + ".wav")
        before = snapshot.read_bytes()
        (self.path / "alihkan.wav").write_bytes(b"invalid new preset")
        self.assertEqual(snapshot.read_bytes(), before)
        self.assertTrue(self.controller.valid_wav(snapshot))

    async def test_idempotent_preset_retry_survives_source_removal_and_offline(self):
        await self.enable()
        first = await self.speak(identifier="preset_retry_123")
        (self.path / "alihkan.wav").unlink()
        self.clock += 20
        second = await self.speak(identifier="preset_retry_123")
        self.assertEqual(first["id"], second["id"])

    async def test_conversion_timeout_kills_process_without_pending_audio(self):
        await self.enable()
        fake = types.SimpleNamespace(returncode=None)
        fake.kill = lambda: setattr(fake, "returncode", -9)
        async def wait():
            return fake.returncode
        fake.wait = wait
        async def create(*args, **kwargs):
            return fake
        real_wait_for = asyncio.wait_for
        async def timeout(coro, timeout):
            coro.close()
            raise asyncio.TimeoutError()
        with patch("remote_core.asyncio.create_subprocess_exec", create), patch("remote_core.asyncio.wait_for", timeout):
            with self.assertRaises(HTTPException) as caught:
                await self.controller.upload(FakeRequest(), UploadFile(io.BytesIO(b"input")))
        self.assertEqual(caught.exception.status_code, 504)
        self.assertEqual(fake.returncode, -9)
        self.assertEqual(self.controller.status()["jobs"], [])
        self.assertEqual(list(self.path.glob(".convert_*")), [])

    async def test_speech_conversion_has_no_metadata_chunk(self):
        await self.enable()
        result = await self.speak()
        data = (self.path / (result["id"] + ".wav")).read_bytes()
        chunks = []
        offset = 12
        while offset + 8 <= len(data):
            chunks.append(data[offset:offset + 4])
            size = int.from_bytes(data[offset + 4:offset + 8], "little")
            offset += 8 + size + (size % 2)
        self.assertEqual(chunks, [b"fmt ", b"data"])

    async def test_legacy_database_schema_migrates_without_loss(self):
        await self.enable()
        job = await self.speak()
        self.controller.db.close()
        database = self.path / "jobs.sqlite"
        db = sqlite3.connect(database)
        db.execute("CREATE TABLE oldjobs AS SELECT id,text,audio_url,status,created_at,expires_at,accepted_at,request_key,fingerprint FROM jobs")
        db.execute("DROP TABLE jobs")
        db.execute("ALTER TABLE oldjobs RENAME TO jobs")
        db.commit();db.close()
        self.controller = RemoteController(self.path, "https://owner.test", database, lambda r: None, lambda: {}, lambda: self.clock)
        self.assertEqual(self.controller.pending()["id"], job["id"])
        self.assertIn("dispatch_count", {r[1] for r in self.controller.db.execute("PRAGMA table_info(jobs)")})


class CurrentCandidateTests(unittest.IsolatedAsyncioTestCase):
    def test_authorization_uses_effective_runtime_key_not_source_default(self):
        # Run only the key assignment/auth function; no server startup or secrets.
        tree = ast.parse((ROOT / "artifacts/remote-fix-20261009/server_candidate.py").read_text(encoding="utf-8"))
        assignment = next(n for n in tree.body if isinstance(n, ast.Assign) and
                          any(isinstance(t, ast.Name) and t.id == "API_KEY" for t in n.targets))
        authorize = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "check_key")
        runtime = {"os": types.SimpleNamespace(environ={"API_KEY": "fixture_effective_key"}),
                   "Request": object, "HTTPException": HTTPException}
        exec(compile(ast.Module(body=[assignment, authorize], type_ignores=[]), "auth_fixture", "exec"), runtime)
        runtime["check_key"](FakeRequest(key="fixture_effective_key"))
        default = ast.literal_eval(assignment.value.args[1])
        with self.assertRaises(HTTPException) as caught:
            runtime["check_key"](FakeRequest(key=default))
        self.assertEqual(caught.exception.status_code, 401)

    async def test_candidate_integrated_contract_and_preserved_features(self):
        import httpx
        candidate = ROOT / "artifacts/remote-fix-20261009/server_candidate.py"
        source = candidate.read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory() as folder:
            temporary = Path(folder)
            (temporary / "static/assets").mkdir(parents=True)
            (temporary / "static/apk").mkdir()
            (temporary / "static/apk/eMAS_Remote_v2.1.apk").write_bytes(b"fixture-apk")
            server = types.ModuleType("fixture_server")
            server.__file__ = str(temporary / "server.py")
            source = source.replace('"/app/remote-state.sqlite3"', repr(str(temporary / "state.sqlite")))
            import os
            previous = Path.cwd()
            os.chdir(temporary)
            try:
                exec(compile(source, server.__file__, "exec"), server.__dict__)
                for key in ("alihkan", "salam", "tepi"):
                    with wave.open(str(Path(server.MEGAFON_DIR) / (key + ".wav")), "wb") as wav:
                        wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(48000);wav.writeframes(b"\0\0" * 4800)
                transport = httpx.ASGITransport(app=server.app)
                async with httpx.AsyncClient(transport=transport, base_url="https://fixture") as client:
                    headers = {"X-API-Key": server.API_KEY}
                    for path in ["/api/remote/status", "/api/megafon/status", "/api/megafon/switch"]:
                        self.assertEqual((await client.get(path)).status_code, 401)
                    status = (await client.get("/api/remote/status", headers=headers)).json()
                    self.assertEqual(status["version"], "2.1")
                    self.assertIsNone(status["telemetry"]["soc_pct"])
                    self.assertFalse(status["aco_enabled"])
                    response = await client.post("/api/megafon/switch", headers=headers, json={"enabled": True})
                    self.assertEqual(response.status_code, 200)
                    self.assertFalse((await client.get("/api/remote/status", headers=headers)).json()["car"]["online"])
                    self.assertEqual((await client.post("/api/megafon/speak", headers=headers, json={"preset": "alihkan"})).status_code, 503)
                    for data in [[], "bad", None]:
                        self.assertEqual((await client.post("/api/car/sync", json=data)).status_code, 400)
                    # None of these requests leave the ASGI fixture.
                    self.assertEqual((await client.post("/api/car/sync", json={"client": "MegaphoneDaemon"})).status_code, 200)
                    first = (await client.post("/api/megafon/speak", headers=headers, json={"preset": "alihkan"})).json()
                    second = (await client.post("/api/megafon/speak", headers=headers, json={"preset": "preset_salam"})).json()
                    self.assertNotEqual(first["id"], second["id"])
                    unrelated = (await client.post("/api/car/sync", json={"client": "TelemetryDaemon"})).json()
                    self.assertNotIn("megaphone", unrelated)
                    delivered = (await client.post("/api/car/sync", json={"client": "MegaphoneDaemon"})).json()
                    self.assertEqual(delivered["megaphone"]["id"], first["id"])
                    acknowledged = (await client.post("/api/car/sync", json={"client": "MegaphoneDaemon", "megaphone_ack": first["id"]})).json()
                    self.assertEqual(acknowledged["megaphone"]["id"], second["id"])
                    self.assertEqual((await client.post("/api/megafon/speak", headers=headers, json={"preset": "terima_kasih"})).status_code, 404)
                    for path in ["/download/apk", "/download/eMAS_Remote_v2.apk", "/download/eMAS_Remote_v2.1.apk"]:
                        self.assertEqual((await client.head(path)).status_code, 200)
                        self.assertEqual((await client.get(path)).content, b"fixture-apk")
                    for path in ["/api/megafon/custom/list", "/api/megafon/presets/list", "/api/avas/state", "/api/car/energy"]:
                        self.assertEqual((await client.get(path)).status_code, 200)
                    for path in ["/api/status", "/api/position", "/api/schedule"]:
                        self.assertEqual((await client.get(path, headers=headers)).status_code, 503)
                    self.assertEqual((await client.post("/api/climate/cool30/start", headers=headers, json={})).status_code, 503)
                    self.assertEqual((await client.post("/api/climate/cool30/stop", headers=headers)).status_code, 503)
                    self.assertEqual((await client.post("/api/megafon/upload", files={"file": ("invalid.webm", b"bad")})).status_code, 401)
                    self.assertEqual((await client.post("/api/megafon/upload", headers=headers, files={"file": ("invalid.webm", b"bad")})).status_code, 422)
                self.assertIsNone(await server.poll_vehicle())
                self.assertIsNone(await server.refresh_token_if_needed())
                self.assertIsNone(await server.ac_keepalive_worker())
                server.REMOTE.db.close()
            finally:
                os.chdir(previous)

    def test_transform_refuses_changed_anchor_and_preserves_ast(self):
        specification = importlib.util.spec_from_file_location("prepare_current", ROOT / "EmasRemoteApp/tools/prepare_current_server.py")
        module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(module)
        baseline = (ROOT / "artifacts/remote-fix-20261009/baseline/server.py").read_text(encoding="utf-8")
        core = (ROOT / "EmasRemoteApp/server/remote_core.py").read_text(encoding="utf-8")
        module.transform(baseline, core)
        with self.assertRaises(ValueError):
            module.transform(baseline.replace('    mega_to_send = None', '    mega_to_send = "changed"'), core)


if __name__ == "__main__":
    suite = unittest.TestSuite()
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(RemoteFixTests))
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(CurrentCandidateTests))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
