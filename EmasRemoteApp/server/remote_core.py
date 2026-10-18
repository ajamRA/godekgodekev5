"""Phone remote v2; no ACO client and no vehicle actuator operations."""
import asyncio
import hashlib
import json
import math
import os
import re
import sqlite3
import time
import uuid
import wave
from pathlib import Path
from urllib.parse import urlsplit, unquote
from fastapi import Request, UploadFile, File, HTTPException


class RemoteController:
    MAX_BYTES = 2 * 1024 * 1024
    MAX_QUEUE = 3
    MAX_DISPATCHES = 5
    RETRY_INTERVAL = 3
    AUDIO_FILTERS = ("highpass=f=120,lowpass=f=5000,afade=t=in:ss=0:d=0.05,"
                     "areverse,afade=t=in:ss=0:d=0.08,areverse,"
                     "loudnorm=I=-16:TP=-2.0,alimiter=limit=0.92")
    PRESETS = {"alihkan": "Tolong alihkan", "salam": "Assalamualaikum",
               "tepi": "Beri laluan", "terima_kasih": "Terima kasih"}

    def __init__(self, audio_dir, base_url, database, authorize, energy_provider, clock=time.time,
                 preset_provider=None, horn_provider=None):
        self.audio_dir = Path(audio_dir)
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        self.base_url = base_url.rstrip("/")
        self.authorize = authorize
        self.energy_provider = energy_provider
        self.clock = clock
        self.preset_provider = preset_provider or (lambda: [])
        self.horn_provider = horn_provider or (lambda: [])
        self.last_seen = 0
        self.playing = False
        self.convert_lock = asyncio.Lock()
        self.db = sqlite3.connect(str(database))
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS jobs (
              id TEXT PRIMARY KEY, text TEXT, audio_url TEXT, status TEXT,
              created_at REAL, expires_at REAL, accepted_at REAL,
              request_key TEXT UNIQUE, fingerprint TEXT);
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value REAL);
        """)
        columns = {row[1] for row in self.db.execute("PRAGMA table_info(jobs)")}
        for name, definition in [("dispatch_count", "INTEGER NOT NULL DEFAULT 0"),
                                 ("last_dispatch_at", "REAL"), ("error", "TEXT")]:
            if name not in columns:
                self.db.execute("ALTER TABLE jobs ADD COLUMN " + name + " " + definition)
        self.db.commit()

    def now(self):
        return float(self.clock())

    def switch(self):
        row = self.db.execute("SELECT value FROM settings WHERE key='switch_until'").fetchone()
        until = float(row[0]) if row else 0
        remaining = max(0, math.ceil(until - self.now()))
        return {"enabled": remaining > 0, "remaining_sec": remaining}

    def expire(self):
        with self.db:
            self.db.execute("UPDATE jobs SET status='expired' WHERE status IN ('queued','claimed') AND expires_at<=?", (self.now(),))
            retired = list(self.db.execute("SELECT id FROM jobs WHERE id NOT IN (SELECT id FROM jobs ORDER BY created_at DESC,rowid DESC LIMIT 200) AND status NOT IN ('queued','claimed')"))
            self.db.execute("DELETE FROM jobs WHERE id NOT IN (SELECT id FROM jobs ORDER BY created_at DESC,rowid DESC LIMIT 200) AND status NOT IN ('queued','claimed')")
        for row in retired:
            if re.fullmatch(r"remote_[a-f0-9]{32}", row["id"]):
                (self.audio_dir / (row["id"] + ".wav")).unlink(missing_ok=True)

    def online(self):
        return self.last_seen > 0 and 0 <= self.now() - self.last_seen < 15

    def ready(self):
        if not self.switch()["enabled"]:
            raise HTTPException(409, "Aktifkan megafon dahulu.")
        if not self.online():
            raise HTTPException(503, "IHU tiada heartbeat. Kereta mungkin tidur atau tiada internet.")

    def valid_wav(self, path):
        try:
            if Path(path).stat().st_size > self.MAX_BYTES:
                return False
            with wave.open(str(path), "rb") as audio:
                if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, 48000, "NONE"):
                    return False
                count = audio.getnframes()
                return 0 < count <= 48000 * 20 and len(audio.readframes(count)) == count * 2
        except (OSError, EOFError, wave.Error):
            return False

    def available_presets(self):
        result = []
        configured = self.preset_provider()
        configured = configured if isinstance(configured, list) else []
        keys = list(self.PRESETS)
        keys.extend(p.get("id") for p in configured if isinstance(p, dict) and
                    p.get("key") not in self.PRESETS and isinstance(p.get("id"), str) and p.get("id") not in keys)
        if self.horn_provider():
            keys.append("horn")
        for key in keys:
            try:
                label, _, _ = self.resolve_audio({"preset": key})
                result.append({"id": key, "label": label})
            except HTTPException:
                continue
        return result

    def local_audio(self, url):
        if not isinstance(url, str) or len(url) > 2048:
            raise HTTPException(400, "Alamat audio tidak sah.")
        try:
            parsed = urlsplit(url)
        except ValueError:
            raise HTTPException(400, "Alamat audio tidak sah.")
        origin = urlsplit(self.base_url)
        if parsed.scheme and (parsed.scheme != origin.scheme or not parsed.netloc):
            raise HTTPException(400, "Alamat audio tidak sah.")
        if parsed.netloc and (parsed.scheme, parsed.netloc) != (origin.scheme, origin.netloc):
            raise HTTPException(400, "Audio mesti daripada pelayan ini.")
        prefix = "/static/megafon/"
        path = unquote(parsed.path)
        if not path.startswith(prefix) or "\\" in path or "\0" in path:
            raise HTTPException(400, "Laluan audio tidak sah.")
        candidate = (self.audio_dir / path[len(prefix):]).resolve()
        if not candidate.is_relative_to(self.audio_dir.resolve()) or candidate.suffix.lower() != ".wav":
            raise HTTPException(400, "Laluan audio tidak sah.")
        if not self.valid_wav(candidate):
            raise HTTPException(404, "Audio belum tersedia atau WAV tidak sah.")
        return candidate

    def resolve_audio(self, data):
        preset = data.get("preset", "")
        label = data.get("text", "")
        if not isinstance(preset, str) or not isinstance(label, str):
            raise HTTPException(400, "Preset atau teks tidak sah.")
        if data.get("audio_url"):
            source = self.local_audio(data["audio_url"])
            custom_horn = source.relative_to(self.audio_dir.resolve()).parts[0] == "custom"
            return label or "Audio tersimpan", source, not custom_horn and preset not in ("horn", "custom")
        if preset in ("horn", "custom"):
            horns = self.horn_provider()
            if not isinstance(horns, list) or not horns or not isinstance(horns[-1], dict):
                raise HTTPException(404, "Bunyi hon tersimpan belum tersedia.")
            horn = horns[-1]
            return str(horn.get("name") or "Hon tersimpan")[:80], self.local_audio(horn.get("audio_url")), False
        if not preset:
            raise HTTPException(400, "Pilih preset atau audio tersimpan. Teks sahaja bukan rakaman suara.")
        configured = self.preset_provider()
        configured = configured if isinstance(configured, list) else []
        matched = next((p for p in configured if isinstance(p, dict) and
                        (p.get("id") == preset or p.get("key") == preset)), None)
        key = matched.get("key", preset) if matched else preset
        if not isinstance(key, str):
            raise HTTPException(400, "Metadata preset tidak sah.")
        if key in self.PRESETS and not (matched and matched.get("has_custom_audio") is True):
            # A missing 'terima_kasih' must never fall back to another spoken phrase.
            url = self.base_url + "/static/megafon/" + key + ".wav"
        elif matched and matched.get("audio_url"):
            url = matched["audio_url"]
        else:
            raise HTTPException(400, "Preset tidak sah.")
        label = (matched.get("name") if matched else None) or self.PRESETS.get(key, label or preset)
        return str(label)[:80], self.local_audio(url), True

    def request_key(self, request):
        key = request.headers.get("Idempotency-Key", "")
        if key and not re.fullmatch(r"[A-Za-z0-9_-]{8,100}", key):
            raise HTTPException(400, "ID permintaan tidak sah.")
        return key or None

    def duplicate(self, key, fingerprint):
        if not key:
            return None
        job = self.db.execute("SELECT * FROM jobs WHERE request_key=?", (key,)).fetchone()
        if job and job["fingerprint"] != fingerprint:
            raise HTTPException(409, "ID permintaan sudah digunakan untuk mesej lain.")
        if job:
            return self.result(dict(job))
        return None

    def result(self, job):
        return {"ok": True, "queued": job["status"] == "queued", "id": job["id"],
                "status": job["status"], "url": job["audio_url"], "playback_confirmed": False}

    def enqueue(self, text, url, key, fingerprint, message_id=None):
        duplicate = self.duplicate(key, fingerprint)
        if duplicate:
            return duplicate
        self.ready()
        self.expire()
        count = self.db.execute("SELECT count(*) FROM jobs WHERE status IN ('queued','claimed')").fetchone()[0]
        if count >= self.MAX_QUEUE:
            raise HTTPException(429, "Baris gilir penuh. Tunggu mesej sebelumnya diterima IHU.")
        now = self.now()
        job = {"id": message_id or "remote_" + uuid.uuid4().hex, "text": text[:80],
               "audio_url": url, "status": "queued", "created_at": now,
               "expires_at": now + min(45, self.switch()["remaining_sec"]),
               "accepted_at": None, "request_key": key, "fingerprint": fingerprint}
        with self.db:
            self.db.execute("INSERT INTO jobs (id,text,audio_url,status,created_at,expires_at,accepted_at,request_key,fingerprint) VALUES (:id,:text,:audio_url,:status,:created_at,:expires_at,:accepted_at,:request_key,:fingerprint)", job)
        return self.result(job)

    async def speak(self, request):
        self.authorize(request)
        try:
            data = await request.json()
        except Exception:
            raise HTTPException(400, "JSON permintaan tidak sah.")
        if not isinstance(data, dict):
            raise HTTPException(400, "Permintaan mesti objek JSON.")
        fingerprint = "preset:" + hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()
        key = self.request_key(request)
        duplicate = self.duplicate(key, fingerprint)
        if duplicate:
            return duplicate
        label, source, speech = self.resolve_audio(data)
        self.ready()
        message_id = "remote_" + uuid.uuid4().hex
        output = self.audio_dir / (message_id + ".wav")
        queued = False
        try:
            # Copy before dispatch; editing a preset cannot change a queued utterance.
            output.write_bytes(source.read_bytes())
            if not self.valid_wav(output):
                raise HTTPException(422, "Audio berubah semasa disalin. Cuba semula.")
            if speech:
                await self.convert_audio(output, output)
            result = self.enqueue(label, self.base_url + "/static/megafon/" + output.name, key, fingerprint, message_id)
            queued = result["id"] == message_id
            return result
        finally:
            if not queued:
                output.unlink(missing_ok=True)

    async def set_switch(self, request):
        self.authorize(request)
        try:
            data = await request.json()
        except Exception:
            raise HTTPException(400, "JSON permintaan tidak sah.")
        if not isinstance(data, dict) or type(data.get("enabled")) is not bool:
            raise HTTPException(400, "enabled mesti true atau false.")
        minutes = data.get("duration_min", 10)
        if type(minutes) is not int or not 1 <= minutes <= 10:
            raise HTTPException(400, "Tempoh mestilah 1 hingga 10 minit.")
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO settings VALUES ('switch_until', ?)",
                            (self.now() + minutes * 60 if data["enabled"] else 0,))
            if not data["enabled"]:
                self.db.execute("UPDATE jobs SET status='cancelled' WHERE status IN ('queued','claimed')")
        return {"ok": True, **self.switch()}

    def sync(self, data):
        # The installed daemon sends no key. Preserve this contract, and never
        # dispatch voice to telemetry-only clients or a diagnostic POST.
        if not isinstance(data, dict) or data.get("client") != "MegaphoneDaemon":
            return None
        self.last_seen = self.now()
        self.playing = data.get("playing") is True
        self.expire()
        events = data.get("diagnostic_events", [])
        if isinstance(events, list):
            for event in events[:40]:
                if not isinstance(event, str) or len(event) > 512:
                    continue
                failure = re.search(r"\bFAIL source=cloud:(remote_[a-f0-9]{32}) reason=(download_failed|invalid_wav|queue_full)\b", event)
                if failure:
                    with self.db:
                        self.db.execute("UPDATE jobs SET status='failed',error=? WHERE id=? AND status='claimed'",
                                        (failure.group(2), failure.group(1)))
        ack = data.get("megaphone_ack")
        if isinstance(ack, str) and len(ack) <= 100:
            with self.db:
                # Its ACK means accepted by the local queue, not audible playback.
                self.db.execute("UPDATE jobs SET status='received', accepted_at=? WHERE id=? AND status='claimed'",
                                (self.now(), ack))
        if not self.switch()["enabled"]:
            return None
        job = self.db.execute("SELECT * FROM jobs WHERE status='claimed' ORDER BY created_at,rowid LIMIT 1").fetchone()
        if job is None:
            job = self.db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created_at,rowid LIMIT 1").fetchone()
        if job is None:
            return None
        if job["last_dispatch_at"] is not None and self.now() - job["last_dispatch_at"] < self.RETRY_INTERVAL:
            return None
        if job["dispatch_count"] >= self.MAX_DISPATCHES:
            with self.db:
                self.db.execute("UPDATE jobs SET status='failed',error='ack_timeout' WHERE id=?", (job["id"],))
            return None
        with self.db:
            self.db.execute("UPDATE jobs SET status='claimed',dispatch_count=dispatch_count+1,last_dispatch_at=? WHERE id=?", (self.now(), job["id"]))
        return {"id": job["id"], "text": job["text"], "audio_url": job["audio_url"],
                "timestamp": int(job["created_at"])}

    @staticmethod
    def number(value, minimum, maximum):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return None
        return value if math.isfinite(value) and minimum <= value <= maximum else None

    def status(self):
        self.expire()
        energy = self.energy_provider()
        energy = energy if isinstance(energy, dict) else {}
        timestamp = self.number(energy.get("updated_at"), 1, self.now() + 5)
        battery = energy.get("battery")
        battery = battery if isinstance(battery, dict) else {}
        soc = energy.get("soc_pct", battery.get("soc_pct"))
        return {"ok": True, "version": "2.1", "aco_enabled": False,
                "car": {"online": self.online(), "last_seen": self.last_seen or None,
                        "playing": self.playing if self.online() else None},
                "telemetry": {"soc_pct": self.number(soc, 0, 100),
                              "range_km": self.number(energy.get("range_km"), 0, 2000),
                              "updated_at": timestamp,
                              "fresh": timestamp is not None and 0 <= self.now() - timestamp < 15,
                              "source": "IHU"},
                "megaphone": {**self.switch(), "playback_confirmation_supported": False},
                "presets": self.available_presets(),
                "jobs": [dict(row) for row in self.db.execute(
                    "SELECT id,text,status,error,request_key,created_at,accepted_at,expires_at,dispatch_count FROM jobs ORDER BY created_at DESC,rowid DESC LIMIT 10")]}

    def pending(self):
        self.expire()
        row = self.db.execute("SELECT id,text,audio_url,created_at FROM jobs WHERE status IN ('queued','claimed') ORDER BY created_at,rowid LIMIT 1").fetchone()
        return {"id": row["id"], "text": row["text"], "audio_url": row["audio_url"], "timestamp": int(row["created_at"])} if row else None

    async def upload(self, request, file):
        self.authorize(request)
        self.ready()
        if self.convert_lock.locked():
            raise HTTPException(429, "Rakaman lain sedang diproses. Cuba selepas ia selesai.")
        async with self.convert_lock:
            content = bytearray()
            while True:
                chunk = await file.read(65536)
                if not chunk:
                    break
                content.extend(chunk)
                if len(content) > self.MAX_BYTES:
                    raise HTTPException(413, "Rakaman melebihi 2 MiB.")
            if not content:
                raise HTTPException(400, "Rakaman kosong.")
            fingerprint = "audio:" + hashlib.sha256(content).hexdigest()
            key = self.request_key(request)
            duplicate = self.duplicate(key, fingerprint)
            if duplicate:
                return duplicate
            message_id = "remote_" + uuid.uuid4().hex
            raw = self.audio_dir / ("." + message_id + ".input")
            temporary = self.audio_dir / ("." + message_id + ".wav")
            output = self.audio_dir / (message_id + ".wav")
            process = None
            queued = False
            try:
                raw.write_bytes(content)
                await self.convert_audio(raw, temporary, locked=True)
                os.replace(temporary, output)
                result = self.enqueue("Rakaman suara", self.base_url + "/static/megafon/" + output.name,
                                      key, fingerprint, message_id)
                queued = result["id"] == message_id
                return result
            except asyncio.TimeoutError:
                raise HTTPException(504, "Penukaran audio melebihi had masa.")
            except FileNotFoundError:
                raise HTTPException(503, "Penukar audio belum tersedia.")
            finally:
                if process is not None and process.returncode is None:
                    process.kill()
                    await process.wait()
                raw.unlink(missing_ok=True)
                temporary.unlink(missing_ok=True)
                if not queued:
                    output.unlink(missing_ok=True)

    def register(self, application):
        @application.get("/api/remote/status")
        async def remote_status(request: Request):
            self.authorize(request)
            return self.status()

    async def convert_audio(self, source, destination, locked=False):
        if not locked:
            if self.convert_lock.locked():
                raise HTTPException(429, "Rakaman lain sedang diproses. Cuba selepas ia selesai.")
            async with self.convert_lock:
                return await self.convert_audio(source, destination, locked=True)
        staged = self.audio_dir / (".convert_" + uuid.uuid4().hex + ".wav")
        process = None
        try:
            process = await asyncio.create_subprocess_exec(
                "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-i", str(source), "-t", "20", "-map", "0:a:0", "-vn", "-af", self.AUDIO_FILTERS,
                "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le", "-f", "wav", str(staged),
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            await asyncio.wait_for(process.wait(), timeout=12)
            if process.returncode != 0 or not self.valid_wav(staged):
                raise HTTPException(422, "Format rakaman tidak dapat ditukar kepada WAV.")
            os.replace(staged, destination)
        except asyncio.TimeoutError:
            raise HTTPException(504, "Penukaran audio melebihi had masa.")
        except FileNotFoundError:
            raise HTTPException(503, "Penukar audio belum tersedia.")
        finally:
            if process is not None and process.returncode is None:
                process.kill()
                await process.wait()
            staged.unlink(missing_ok=True)


def install_remote_api(application, authorize, energy_provider, audio_dir, base_url, database,
                       preset_provider=None, horn_provider=None):
    controller = RemoteController(audio_dir, base_url, database, authorize, energy_provider,
                                  preset_provider=preset_provider, horn_provider=horn_provider)
    controller.register(application)
    return controller
