import asyncio
import io
import tempfile
import unittest
import wave
from pathlib import Path
from fastapi import HTTPException, UploadFile
from remote_core import RemoteController


class FakeRequest:
    def __init__(self, data=None, key="owner", identifier=None):
        self.data = data
        self.headers = {"X-API-Key": key}
        if identifier:
            self.headers["Idempotency-Key"] = identifier

    async def json(self):
        if isinstance(self.data, Exception):
            raise self.data
        return self.data


class RemoteTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name)
        self.clock = 1000
        self.energy = {}
        def authorize(request):
            if request.headers.get("X-API-Key") != "owner":
                raise HTTPException(401, "bad key")
        self.controller = RemoteController(self.path, "https://owner.test", self.path / "jobs.sqlite", authorize, lambda: self.energy, lambda: self.clock)
        for preset in ["alihkan", "salam", "tepi"]:
            with wave.open(str(self.path / (preset + ".wav")), "wb") as out:
                out.setnchannels(1);out.setsampwidth(2);out.setframerate(48000);out.writeframes(b"\0\0"*4800)

    def tearDown(self):
        self.controller.db.close()
        self.directory.cleanup()

    async def enable(self):
        await self.controller.set_switch(FakeRequest({"enabled": True, "duration_min": 10}))
        self.controller.sync({"client": "MegaphoneDaemon", "playing": False})

    async def speak(self, preset="alihkan", identifier=None):
        return await self.controller.speak(FakeRequest({"preset": preset}, identifier=identifier))

    async def test_auth_on_switch_speak_and_upload(self):
        for action in [lambda: self.controller.set_switch(FakeRequest({"enabled": True}, key="bad")),
                       lambda: self.controller.speak(FakeRequest({"preset": "alihkan"}, key="bad")),
                       lambda: self.controller.upload(FakeRequest(key="bad"), UploadFile(io.BytesIO(b"audio")))]:
            with self.assertRaises(HTTPException) as caught:
                await action()
            self.assertEqual(caught.exception.status_code, 401)

    async def test_switch_off_rejects_audio(self):
        with self.assertRaises(HTTPException) as caught:
            await self.speak()
        self.assertEqual(caught.exception.status_code, 409)

    async def test_offline_rejects_even_when_enabled(self):
        await self.controller.set_switch(FakeRequest({"enabled": True}))
        with self.assertRaises(HTTPException) as caught:
            await self.speak()
        self.assertEqual(caught.exception.status_code, 503)

    async def test_two_messages_remain_in_fifo_order(self):
        await self.enable()
        first = await self.speak("alihkan")
        second = await self.speak("salam")
        sent = self.controller.sync({"client": "MegaphoneDaemon"})
        self.assertEqual(sent["id"], first["id"])
        self.assertEqual(self.controller.sync({"client": "MegaphoneDaemon"})["id"], first["id"])
        sent = self.controller.sync({"client": "MegaphoneDaemon", "megaphone_ack": first["id"]})
        self.assertEqual(sent["id"], second["id"])
        jobs = self.controller.status()["jobs"]
        self.assertEqual(next(j for j in jobs if j["id"] == first["id"])["status"], "received")
        self.assertNotIn("played", [j["status"] for j in jobs])

    async def test_random_sync_cannot_consume_queue(self):
        await self.enable();await self.speak()
        self.assertIsNone(self.controller.sync({}))
        self.assertIsNone(self.controller.sync({"client": "TelemetryDaemon"}))
        self.assertEqual(self.controller.pending()["id"], self.controller.status()["jobs"][0]["id"])

    async def test_queue_bound(self):
        await self.enable()
        for _ in range(3): await self.speak()
        with self.assertRaises(HTTPException) as caught: await self.speak()
        self.assertEqual(caught.exception.status_code, 429)

    async def test_idempotency_does_not_duplicate_or_change_payload(self):
        await self.enable()
        first = await self.speak(identifier="same_message_123")
        second = await self.speak(identifier="same_message_123")
        self.assertEqual(first["id"], second["id"])
        self.assertEqual(len(self.controller.status()["jobs"]), 1)
        with self.assertRaises(HTTPException): await self.speak("salam", identifier="same_message_123")

    async def test_expiry_never_dispatches_delayed_voice(self):
        await self.enable();await self.speak();self.clock += 46
        self.assertIsNone(self.controller.sync({"client": "MegaphoneDaemon"}))
        self.assertEqual(self.controller.status()["jobs"][0]["status"], "expired")

    async def test_switch_off_cancels_waiting_job(self):
        await self.enable();await self.speak()
        await self.controller.set_switch(FakeRequest({"enabled": False}))
        self.assertIsNone(self.controller.sync({"client": "MegaphoneDaemon"}))
        self.assertEqual(self.controller.status()["jobs"][0]["status"], "cancelled")

    async def test_switch_validation(self):
        for data in [{"enabled": "false"}, {"enabled": True, "duration_min": -1}, {"enabled": True, "duration_min": True}, []]:
            with self.assertRaises(HTTPException) as caught: await self.controller.set_switch(FakeRequest(data))
            self.assertEqual(caught.exception.status_code, 400)

    async def test_missing_or_traversal_preset(self):
        await self.enable()
        for preset, status in [("terima_kasih", 404), ("../other", 400)]:
            with self.assertRaises(HTTPException) as caught: await self.speak(preset)
            self.assertEqual(caught.exception.status_code, status)
        self.assertEqual(len(self.controller.status()["presets"]), 3)

    async def test_no_sample_telemetry_and_stale_is_explicit(self):
        state = self.controller.status()
        self.assertIsNone(state["telemetry"]["soc_pct"])
        self.energy.update({"soc_pct": 38, "battery": {"soc_pct": 82}, "updated_at": 1000})
        self.assertEqual(self.controller.status()["telemetry"]["soc_pct"], 38)
        self.assertTrue(self.controller.status()["telemetry"]["fresh"])
        self.clock += 20
        self.assertFalse(self.controller.status()["telemetry"]["fresh"])
        self.energy["soc_pct"] = True
        self.assertIsNone(self.controller.status()["telemetry"]["soc_pct"])

    async def test_aco_disabled_and_state_persists_restart(self):
        await self.enable();first=await self.speak()
        self.controller.db.close()
        self.controller = RemoteController(self.path, "https://owner.test", self.path / "jobs.sqlite", lambda r: None, lambda: {}, lambda: self.clock)
        self.assertFalse(self.controller.status()["aco_enabled"])
        self.assertFalse(self.controller.online())
        self.assertEqual(self.controller.pending()["id"], first["id"])

    async def test_bad_conversion_never_queues_raw_as_wav(self):
        await self.enable()
        with self.assertRaises(HTTPException) as caught:
            await self.controller.upload(FakeRequest(), UploadFile(io.BytesIO(b"invalid webm")))
        self.assertEqual(caught.exception.status_code, 422)
        self.assertEqual(self.controller.status()["jobs"], [])
        self.assertEqual(list(self.path.glob("remote_*.wav")), [])
        self.assertEqual(list(self.path.glob(".remote_*")), [])

    async def test_actual_ffmpeg_conversion_and_limit(self):
        await self.enable()
        recording = (self.path / "alihkan.wav").read_bytes()
        result = await self.controller.upload(FakeRequest(), UploadFile(io.BytesIO(recording)))
        self.assertTrue(self.controller.valid_wav(self.path / (result["id"] + ".wav")))
        with self.assertRaises(HTTPException) as caught:
            await self.controller.upload(FakeRequest(), UploadFile(io.BytesIO(b"x"*(self.controller.MAX_BYTES+1))))
        self.assertEqual(caught.exception.status_code, 413)


if __name__ == "__main__": unittest.main(verbosity=2)
