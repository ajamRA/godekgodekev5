"""Local ASGI tests; no network, car, daemon, or deployed server is used."""
import asyncio
import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import wave

import httpx
from fastapi import FastAPI, HTTPException

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from avas_remote import install_avas_remote, validate_wav


def wav(rate=48000, channels=1, width=2, frames=480):
    output = io.BytesIO()
    with wave.open(output, "wb") as stream:
        stream.setnchannels(channels)
        stream.setsampwidth(width)
        stream.setframerate(rate)
        stream.writeframes(b"\x01" * frames * channels * width)
    return output.getvalue()


class AvasRemoteTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.clock = [1000.0]
        self.app = FastAPI()
        def authorize(request):
            if request.headers.get("X-API-Key") != "fixture-key":
                raise HTTPException(401, "bad key")
        self.authorize = authorize
        self.controller = install_avas_remote(self.app, authorize, Path(self.temp.name) / "audio",
                                               Path(self.temp.name) / "state.sqlite3", "fixture-ihu", lambda: self.clock[0])
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="https://fixture",
                                       headers={"X-API-Key": "fixture-key"})
        self.report = {"device_id": "fixture-ihu", "bridge_protocol": 1, "daemon_protocol": 2,
                       "audio_revision": "20261014", "status": {"ok": True, "is_android": True,
                       "protocol_version": 2, "audio_revision": "20261014", "active_slot": 1}}

    async def asyncTearDown(self):
        await self.client.aclose()
        self.controller.db.close()
        self.temp.cleanup()

    async def upload(self, data=None):
        response = await self.client.post("/api/remote/avas/upload", files={"file": ("custom.wav", wav() if data is None else data, "audio/wav")})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()["upload"]

    async def sync(self, **updates):
        return await self.client.post("/api/remote/avas/sync", json={**self.report, **updates})

    async def apply(self, upload, request_id="request-0001", **updates):
        return await self.client.post("/api/remote/avas/apply", json={"slot": 1, "upload_id": upload["upload_id"], "request_id": request_id, **updates})

    async def test_upload_stages_without_job_and_authenticated_download_is_exact(self):
        data = wav()
        upload = await self.upload(data)
        state = (await self.client.get("/api/remote/avas/state")).json()
        self.assertIsNone(state["job"])
        self.assertFalse(state["ready"])
        download = await self.client.get("/api/remote/avas/audio/" + upload["upload_id"])
        self.assertEqual(download.content, data)
        self.assertEqual(upload["bytes"], len(data))
        self.assertEqual((await self.apply(upload)).status_code, 409)

    async def test_all_routes_require_authentication(self):
        for method, path, kwargs in [
            ("GET", "state", {}), ("POST", "apply", {"json": {}}), ("POST", "restore", {"json": {}}),
            ("POST", "sync", {"json": self.report}), ("GET", "jobs/" + "a" * 32, {}),
            ("GET", "audio/" + "a" * 32, {}),
            ("POST", "upload", {"files": {"file": ("test.wav", wav())}}),
        ]:
            response = await self.client.request(method, "/api/remote/avas/" + path, headers={"X-API-Key": "wrong"}, **kwargs)
            self.assertEqual(response.status_code, 401, path)

    async def test_claim_once_ack_and_terminal_request_id_replay(self):
        upload = await self.upload()
        await self.sync()
        queued = (await self.apply(upload)).json()["job"]
        self.assertEqual(queued["status"], "queued")
        self.assertEqual((await self.apply(upload)).json()["job"]["id"], queued["id"])
        dispatch = (await self.sync()).json()["job"]
        self.assertEqual(dispatch["id"], queued["id"])
        self.assertEqual(dispatch["status"], "dispatching")
        self.assertEqual(dispatch["audio_path"], "/api/remote/avas/audio/" + upload["upload_id"])
        self.assertIsNone((await self.sync()).json()["job"])
        self.assertEqual((await self.apply(upload, request_id="request-0002")).status_code, 409)
        ack = {"id": queued["id"], "status": "applied"}
        self.assertEqual((await self.sync(result=ack)).status_code, 200)
        self.assertEqual((await self.sync(result=ack)).status_code, 200)
        replay = (await self.apply(upload)).json()["job"]
        self.assertEqual(replay["status"], "applied")
        self.assertEqual(replay["id"], queued["id"])
        self.assertEqual((await self.apply(upload, slot=2)).status_code, 409)

    async def test_capability_and_identity_must_match_and_mock_is_blocked(self):
        upload = await self.upload()
        wrong_reports = [
            {"bridge_protocol": 2}, {"daemon_protocol": 1}, {"audio_revision": "old"},
            {"status": {**self.report["status"], "is_android": False}},
            {"status": {**self.report["status"], "ok": False}},
            {"status": {**self.report["status"], "protocol_version": 1}},
            {"status": {**self.report["status"], "audio_revision": "old"}},
        ]
        for updates in wrong_reports:
            self.assertEqual((await self.sync(**updates)).status_code, 200)
            self.assertEqual((await self.apply(upload)).status_code, 409, updates)
        self.assertEqual((await self.sync(device_id="other-ihu")).status_code, 403)
        await self.sync()
        self.clock[0] += 30
        self.assertEqual((await self.apply(upload)).status_code, 409)

    async def test_queue_expiry_dispatch_timeout_and_no_automatic_retry(self):
        upload = await self.upload()
        await self.sync()
        expired_id = (await self.apply(upload)).json()["job"]["id"]
        self.clock[0] += 120
        self.assertIsNone((await self.sync()).json()["job"])
        self.assertEqual((await self.apply(upload)).json()["job"]["status"], "expired")
        queued = (await self.apply(upload, request_id="request-0002")).json()["job"]
        await self.sync()
        self.clock[0] += 120
        self.assertIsNone((await self.sync()).json()["job"])
        result = (await self.client.get("/api/remote/avas/jobs/" + queued["id"])).json()["job"]
        self.assertEqual(result["status"], "uncertain")
        self.assertEqual((await self.apply(upload, request_id="request-0002")).json()["job"]["id"], queued["id"])
        self.assertNotEqual(expired_id, queued["id"])

    async def test_ack_only_dispatched_job_and_error_is_sanitized(self):
        upload = await self.upload()
        await self.sync()
        job = (await self.apply(upload)).json()["job"]
        ack = {"id": job["id"], "status": "failed", "error": "token-secret /system/bin/command"}
        self.assertEqual((await self.sync(result=ack)).status_code, 409)
        self.assertEqual((await self.sync(result={**ack, "id": "a" * 32})).status_code, 409)
        await self.sync()
        self.assertEqual((await self.sync(result=ack)).status_code, 200)
        state = await self.client.get("/api/remote/avas/state")
        self.assertNotIn("token-secret", state.text)
        self.assertEqual(state.json()["job"]["status"], "failed")
        self.assertEqual((await self.apply(upload)).json()["job"]["id"], job["id"])

    async def test_persistence_never_redispatches_claimed_job_after_restart(self):
        upload = await self.upload()
        await self.sync()
        job = (await self.apply(upload)).json()["job"]
        await self.sync()
        app = FastAPI()
        other = install_avas_remote(app, self.authorize, Path(self.temp.name) / "audio", Path(self.temp.name) / "state.sqlite3", "fixture-ihu", lambda: self.clock[0])
        try:
            self.assertIsNone(other.sync(self.report)["job"])
            self.assertEqual(other.state()["job"]["id"], job["id"])
            self.assertEqual(other.state()["job"]["status"], "dispatching")
        finally:
            other.db.close()

    async def test_blank_target_disables_mutations(self):
        self.controller.device_id = ""
        self.assertEqual((await self.client.post("/api/remote/avas/upload", files={"file": ("x.wav", wav())})).status_code, 503)
        self.assertEqual((await self.sync()).status_code, 503)
        self.assertFalse((await self.client.get("/api/remote/avas/state")).json()["enabled"])

    async def test_restore_has_no_audio_and_slot_validation_is_strict(self):
        await self.sync()
        for slot in [True, "1", 0, 4, 1.0]:
            response = await self.client.post("/api/remote/avas/restore", json={"slot": slot, "request_id": "restore-0001"})
            self.assertEqual(response.status_code, 422)
        response = await self.client.post("/api/remote/avas/restore", json={"slot": 3, "request_id": "restore-0001"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["job"]["action"], "restore")
        self.assertNotIn("audio_path", response.json()["job"])

    async def test_corrupted_staged_file_is_never_downloaded(self):
        upload = await self.upload()
        path = Path(self.temp.name) / "audio" / (upload["upload_id"] + ".wav")
        path.write_bytes(wav(frames=481))
        response = await self.client.get("/api/remote/avas/audio/" + upload["upload_id"])
        self.assertEqual(response.status_code, 409)

    async def test_multipart_bound_and_json_bound_reject_before_writing(self):
        response = await self.client.post("/api/remote/avas/upload", files={"file": ("huge.wav", b"x" * (8 * 1024 * 1024 + 32768))})
        self.assertEqual(response.status_code, 413)
        self.assertEqual(list((Path(self.temp.name) / "audio").iterdir()), [])
        response = await self.client.post("/api/remote/avas/apply", content=b" " * 32769)
        self.assertEqual(response.status_code, 413)
        response = await self.client.post("/api/remote/avas/upload", content=b"malformed", headers={"X-API-Key": "wrong"})
        self.assertEqual(response.status_code, 401)

    async def test_late_exact_ack_resolves_uncertainty_without_redispatch(self):
        upload = await self.upload()
        await self.sync()
        job = (await self.apply(upload)).json()["job"]
        await self.sync()
        self.clock[0] += 121
        self.assertEqual(self.controller.state()["job"]["status"], "uncertain")
        response = await self.sync(result={"id": job["id"], "status": "applied"})
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["job"])
        self.assertEqual(self.controller.state()["job"]["status"], "applied")

    async def test_second_process_serializes_active_job(self):
        upload = await self.upload()
        await self.sync()
        self.assertEqual((await self.apply(upload)).status_code, 200)
        other = install_avas_remote(FastAPI(), self.authorize, Path(self.temp.name) / "audio", Path(self.temp.name) / "state.sqlite3", "fixture-ihu", lambda: self.clock[0])
        try:
            with self.assertRaises(HTTPException) as error:
                other.enqueue("restore", {"slot": 1, "request_id": "restore-0002"})
            self.assertEqual(error.exception.status_code, 409)
        finally:
            other.db.close()

    async def test_daemon_uncertain_remains_terminal_and_request_id_is_public(self):
        upload = await self.upload()
        await self.sync()
        job = (await self.apply(upload)).json()["job"]
        self.assertEqual(job["request_id"], "request-0001")
        await self.sync()
        ack = {"id": job["id"], "status": "uncertain"}
        self.assertEqual((await self.sync(result=ack)).status_code, 200)
        self.assertEqual((await self.sync(result={**ack, "status": "applied"})).status_code, 409)
        self.assertEqual(self.controller.state()["job"]["status"], "uncertain")
        self.assertEqual((await self.apply(upload, request_id="request-0002")).status_code, 409)

    async def test_public_config_preserves_slot_names_without_paths_or_secrets(self):
        status = {**self.report["status"], "token": "secret-value", "shell": "dangerous-command",
                  "slots": {"1": {"custom_name": "Nama saya", "source": "custom", "audio_sha256": "a" * 64,
                                     "audio_file": "/private/audio.wav", "secret": "secret-value"},
                            "arbitrary-path": {"custom_name": "bad"}}}
        await self.sync(status=status)
        response = await self.client.get("/api/remote/avas/state")
        public = response.json()["device_config"]
        self.assertEqual(public["slots"]["1"]["custom_name"], "Nama saya")
        self.assertEqual(list(public["slots"]), ["1"])
        self.assertNotIn("secret-value", response.text)
        self.assertNotIn("dangerous-command", response.text)
        self.assertNotIn("/private/", response.text)

    def test_strict_complete_riff_validation(self):
        good = wav()
        silent = good[:44] + bytes(len(good) - 44)
        duplicate = good + b"data" + struct.pack("<I", 2) + b"\x01\x00"
        duplicate = duplicate[:4] + struct.pack("<I", len(duplicate) - 8) + duplicate[8:]
        invalid = [b"", good[:-1], good + b"extra", wav(rate=44100), wav(channels=2), wav(width=1),
                   wav(frames=0), silent, duplicate, wav(frames=48000 * 60 + 1)]
        for data in invalid:
            with self.assertRaises(HTTPException):
                validate_wav(data)
        self.assertEqual(validate_wav(good), 0.01)


if __name__ == "__main__":
    unittest.main()
