"""Authenticated, persistent AVAS mailbox. Uploading never changes the vehicle.

The IHU bridge polls this mailbox and executes each claimed command at most once.
An applied acknowledgement confirms a daemon result, never audible playback.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import struct
import threading
import time
import uuid

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException, MultiPartParser


MAX_BYTES = 8 * 1024 * 1024
MAX_SECONDS = 60
ID = re.compile(r"^[a-f0-9]{32}$")
REQUEST_ID = re.compile(r"^[A-Za-z0-9_.-]{8,128}$")
TIMEOUT_ERROR = "Tiada pengesahan IHU; jangan ulang secara automatik."


def validate_wav(data):
    """Validate the complete RIFF container without decoding or transforming it."""
    if len(data) > MAX_BYTES:
        raise HTTPException(413, "WAV melebihi 8 MiB.")
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise HTTPException(422, "Fail mesti WAV RIFF PCM.")
    if struct.unpack_from("<I", data, 4)[0] + 8 != len(data):
        raise HTTPException(422, "Saiz RIFF tidak sepadan atau fail terpotong.")
    offset, chunks = 12, {}
    while offset < len(data):
        if offset + 8 > len(data):
            raise HTTPException(422, "Pengepala chunk WAV terpotong.")
        kind, size = struct.unpack_from("<4sI", data, offset)
        start = offset + 8
        end = start + size
        if kind in chunks or end + (size & 1) > len(data):
            raise HTTPException(422, "Chunk WAV berulang atau terpotong.")
        chunks[kind] = data[start:end]
        offset = end + (size & 1)
    fmt, samples = chunks.get(b"fmt "), chunks.get(b"data")
    if fmt is None or len(fmt) not in (16, 18) or (len(fmt) == 18 and fmt[16:] != b"\0\0"):
        raise HTTPException(422, "Format PCM WAV tidak sah.")
    if struct.unpack_from("<HHIIHH", fmt) != (1, 1, 48000, 96000, 2, 16):
        raise HTTPException(422, "WAV mesti PCM 48 kHz, mono, 16-bit.")
    if samples is None or not samples or len(samples) % 2 or len(samples) > MAX_SECONDS * 96000:
        raise HTTPException(422, "Audio mesti berisi dan tidak melebihi 60 saat.")
    if not any(samples):
        raise HTTPException(422, "Audio senyap tidak diterima.")
    return len(samples) / 96000


class AvasRemote:
    def __init__(self, authorize, audio_dir, database, device_id="", now=time.time):
        self.authorize, self.now = authorize, now
        self.device_id = str(device_id).strip()
        self.audio_dir = Path(audio_dir).resolve()
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(database), check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS avas_uploads (
            upload_id TEXT PRIMARY KEY, name TEXT NOT NULL, sha256 TEXT NOT NULL,
            bytes INTEGER NOT NULL, duration_seconds REAL NOT NULL, created_at REAL NOT NULL);
          CREATE TABLE IF NOT EXISTS avas_jobs (
            id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL, fingerprint TEXT NOT NULL,
            device_id TEXT NOT NULL, action TEXT NOT NULL, slot INTEGER NOT NULL,
            upload_id TEXT, status TEXT NOT NULL, created_at REAL NOT NULL,
            expires_at REAL NOT NULL, dispatched_at REAL, completed_at REAL, error TEXT);
          CREATE TABLE IF NOT EXISTS avas_device (
            device_id TEXT PRIMARY KEY, last_seen REAL NOT NULL,
            capable INTEGER NOT NULL, config TEXT NOT NULL);
        """)

    def expire(self):
        now = self.now()
        self.db.execute("UPDATE avas_jobs SET status='expired',completed_at=? WHERE status='queued' AND expires_at<=?", (now, now))
        self.db.execute("UPDATE avas_jobs SET status='uncertain',completed_at=?,error=? WHERE status='dispatching' AND dispatched_at<=?", (now, TIMEOUT_ERROR, now - 120))

    def device(self):
        return self.db.execute("SELECT * FROM avas_device WHERE device_id=?", (self.device_id,)).fetchone()

    def online(self, row):
        return bool(row and 0 <= self.now() - row["last_seen"] < 30)

    def ready(self):
        if not self.device_id:
            raise HTTPException(503, "Sasaran AVAS belum dikonfigurasi.")
        row = self.device()
        if not self.online(row) or not row["capable"]:
            raise HTTPException(409, "IHU AVAS belum bersedia atau di luar talian.")

    def upload_info(self, upload_id):
        row = self.db.execute("SELECT * FROM avas_uploads WHERE upload_id=?", (upload_id,)).fetchone()
        return dict(row) if row else None

    def job_info(self, row):
        if not row:
            return None
        result = {key: row[key] for key in ("id", "request_id", "action", "slot", "status", "created_at", "expires_at", "dispatched_at", "completed_at", "error")}
        if row["upload_id"]:
            upload = self.upload_info(row["upload_id"])
            if upload:
                result.update({key: upload[key] for key in ("upload_id", "sha256", "bytes", "name")})
                result["audio_path"] = "/api/remote/avas/audio/" + upload["upload_id"]
        return result

    def state(self):
        with self.lock:
            self.expire()
            device = self.device()
            job = self.db.execute("SELECT * FROM avas_jobs WHERE device_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1", (self.device_id,)).fetchone()
            return {"ok": True, "enabled": bool(self.device_id), "device_id": self.device_id or None,
                    "device_online": self.online(device), "ready": bool(self.online(device) and device["capable"]), "last_seen": device["last_seen"] if device else None,
                    "device_config": json.loads(device["config"]) if device else None,
                    "job": self.job_info(job), "uploads": [dict(row) for row in self.db.execute("SELECT * FROM avas_uploads ORDER BY created_at DESC LIMIT 50")]}

    async def upload(self, file, name):
        if not self.device_id:
            raise HTTPException(503, "Sasaran AVAS belum dikonfigurasi.")
        content = bytearray()
        while True:
            chunk = await file.read(65536)
            if not chunk:
                break
            content.extend(chunk)
            if len(content) > MAX_BYTES:
                raise HTTPException(413, "WAV melebihi 8 MiB.")
        duration = validate_wav(content)
        upload_id = uuid.uuid4().hex
        # Filenames supplied by clients never become filesystem paths.
        label = self.safe_name(name or file.filename or "Audio AVAS")
        output = self.audio_dir / (upload_id + ".wav")
        with output.open("xb") as stream:
            stream.write(content)
        try:
            with self.lock:
                self.db.execute("INSERT INTO avas_uploads VALUES(?,?,?,?,?,?)", (upload_id, label, hashlib.sha256(content).hexdigest(), len(content), duration, self.now()))
                return {"ok": True, "upload": self.upload_info(upload_id)}
        except BaseException:
            output.unlink(missing_ok=True)
            raise

    def enqueue(self, action, body):
        if not isinstance(body, dict):
            raise HTTPException(422, "Permintaan JSON tidak sah.")
        slot, request_id = body.get("slot"), body.get("request_id")
        upload_id = body.get("upload_id") if action == "apply" else None
        if type(slot) is not int or slot not in (1, 2, 3):
            raise HTTPException(422, "Slot mesti 1, 2 atau 3.")
        if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
            raise HTTPException(422, "request_id mesti 8 hingga 128 aksara selamat.")
        if action == "apply" and (not isinstance(upload_id, str) or not ID.fullmatch(upload_id)):
            raise HTTPException(422, "upload_id tidak sah.")
        fingerprint = json.dumps([self.device_id, action, slot, upload_id], separators=(",", ":"))
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                self.expire()
                existing = self.db.execute("SELECT * FROM avas_jobs WHERE request_id=?", (request_id,)).fetchone()
                if existing:
                    if existing["fingerprint"] != fingerprint:
                        raise HTTPException(409, "request_id telah digunakan untuk arahan berbeza.")
                    result = self.job_info(existing)
                else:
                    self.ready()
                    if self.db.execute("SELECT 1 FROM avas_jobs WHERE status IN ('queued','dispatching','uncertain')").fetchone():
                        raise HTTPException(409, "Arahan AVAS lain masih diproses.")
                    if upload_id and not self.upload_info(upload_id):
                        raise HTTPException(404, "Audio tidak ditemui.")
                    now, job_id = self.now(), uuid.uuid4().hex
                    self.db.execute("INSERT INTO avas_jobs(id,request_id,fingerprint,device_id,action,slot,upload_id,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,'queued',?,?)", (job_id, request_id, fingerprint, self.device_id, action, slot, upload_id, now, now + 120))
                    result = self.job_info(self.db.execute("SELECT * FROM avas_jobs WHERE id=?", (job_id,)).fetchone())
                self.db.execute("COMMIT")
                return {"ok": True, "job": result}
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    @staticmethod
    def safe_name(value):
        # Java's bridge length bound counts UTF-16 code units.
        value = "".join(c for c in str(value) if c.isprintable()).replace("\\", "_").replace("/", "_")
        return value.encode("utf-16-le", errors="ignore")[:160].decode("utf-16-le", errors="ignore") or "Audio AVAS"

    @staticmethod
    def safe_config(status):
        # Never relay arbitrary daemon properties, command lines, paths or secrets.
        result = {}
        for key in ("ok", "ready", "is_android", "protocol_version", "audio_revision", "selected_slot", "active_slot", "switch", "persistence_version", "reapply_on_boot", "boot_hook_verified"):
            value = status.get(key)
            if type(value) in (bool, int) or (isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,40}", value)):
                result[key] = value
        if isinstance(status.get("slots"), dict):
            result["slots"] = {}
            for slot in ("1", "2", "3"):
                item = status["slots"].get(slot)
                if not isinstance(item, dict):
                    continue
                sanitized = {}
                if isinstance(item.get("custom_name"), str):
                    sanitized["custom_name"] = AvasRemote.safe_name(item["custom_name"])
                if item.get("source") in ("unverified", "custom", "factory_backup"):
                    sanitized["source"] = item["source"]
                if isinstance(item.get("audio_sha256"), str) and re.fullmatch(r"[a-f0-9]{64}", item["audio_sha256"]):
                    sanitized["audio_sha256"] = item["audio_sha256"]
                result["slots"][slot] = sanitized
        return result

    def sync(self, body):
        if not self.device_id:
            raise HTTPException(503, "Sasaran AVAS belum dikonfigurasi.")
        if not isinstance(body, dict) or body.get("device_id") != self.device_id:
            raise HTTPException(403, "Identiti IHU tidak sepadan.")
        config = body.get("status")
        if not isinstance(config, dict):
            raise HTTPException(422, "Status daemon diperlukan.")
        capable = (type(body.get("bridge_protocol")) is int and body["bridge_protocol"] == 1
                   and type(body.get("daemon_protocol")) is int and body["daemon_protocol"] == 2
                   and body.get("audio_revision") == "20261014" and config.get("is_android") is True
                   and config.get("ok") is True and type(config.get("protocol_version")) is int
                   and config["protocol_version"] == 2 and config.get("audio_revision") == "20261014")
        ack = body.get("result")
        if ack is not None and (not isinstance(ack, dict) or not isinstance(ack.get("id"), str)
                                or not ID.fullmatch(ack["id"]) or ack.get("status") not in ("applied", "failed", "uncertain")):
            raise HTTPException(422, "Pengesahan arahan tidak sah.")
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                self.expire()
                if ack is not None:
                    job = self.db.execute("SELECT * FROM avas_jobs WHERE id=? AND device_id=?", (ack["id"], self.device_id)).fetchone()
                    if not job or job["dispatched_at"] is None:
                        raise HTTPException(409, "Arahan belum dihantar kepada IHU ini.")
                    if job["status"] == "dispatching" or (job["status"] == "uncertain" and job["error"] == TIMEOUT_ERROR and ack["status"] in ("applied", "failed")):
                        # Error text may contain sensitive shell output; use a fixed public message.
                        error = None if ack["status"] == "applied" else "IHU melaporkan kegagalan." if ack["status"] == "failed" else "Keputusan IHU tidak dapat dipastikan."
                        self.db.execute("UPDATE avas_jobs SET status=?,completed_at=?,error=? WHERE id=?", (ack["status"], self.now(), error, job["id"]))
                    elif job["status"] != ack["status"]:
                        raise HTTPException(409, "Keputusan arahan telah dimuktamadkan.")
                self.db.execute("INSERT OR REPLACE INTO avas_device VALUES(?,?,?,?)", (self.device_id, self.now(), int(capable), json.dumps(self.safe_config(config))))
                job = self.db.execute("SELECT * FROM avas_jobs WHERE device_id=? AND status='queued' ORDER BY created_at,rowid LIMIT 1", (self.device_id,)).fetchone() if capable else None
                result = None
                if job:
                    self.db.execute("UPDATE avas_jobs SET status='dispatching',dispatched_at=? WHERE id=? AND status='queued'", (self.now(), job["id"]))
                    result = self.job_info(self.db.execute("SELECT * FROM avas_jobs WHERE id=?", (job["id"],)).fetchone())
                self.db.execute("COMMIT")
                return {"ok": True, "job": result}
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def register(self, app):
        @app.get("/api/remote/avas/state")
        async def avas_state(request: Request):
            self.authorize(request)
            return self.state()

        @app.post("/api/remote/avas/upload")
        async def avas_upload(request: Request):
            self.authorize(request)
            if not self.device_id:
                raise HTTPException(503, "Sasaran AVAS belum dikonfigurasi.")
            # Bound the complete multipart stream before the parser can spool files.
            # Authenticate first, including requests with malformed/missing multipart.
            over_limit = False
            async def bounded_stream():
                nonlocal over_limit
                size = 0
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > MAX_BYTES + 16384:
                        over_limit = True
                        raise MultiPartException("Upload exceeds limit")
                    yield chunk
            try:
                form = await MultiPartParser(request.headers, bounded_stream(), max_files=1, max_fields=1).parse()
            except MultiPartException:
                raise HTTPException(413 if over_limit else 422, "Muat naik WAV terlalu besar." if over_limit else "Borang muat naik tidak sah.")
            try:
                fields = list(form.multi_items())
                if any(key not in ("file", "name") for key, _ in fields) or len(fields) != len(set(key for key, _ in fields)):
                    raise HTTPException(422, "Medan muat naik tidak sah.")
                file, name = form.get("file"), form.get("name", "")
                if not isinstance(file, UploadFile) or not isinstance(name, str):
                    raise HTTPException(422, "Fail WAV diperlukan.")
                return await self.upload(file, name)
            finally:
                await form.close()

        @app.post("/api/remote/avas/apply")
        async def avas_apply(request: Request):
            self.authorize(request)
            return self.enqueue("apply", await self.read_json(request))

        @app.post("/api/remote/avas/restore")
        async def avas_restore(request: Request):
            self.authorize(request)
            return self.enqueue("restore", await self.read_json(request))

        @app.post("/api/remote/avas/sync")
        async def avas_sync(request: Request):
            self.authorize(request)
            return self.sync(await self.read_json(request))

        @app.get("/api/remote/avas/jobs/{job_id}")
        async def avas_job(job_id: str, request: Request):
            self.authorize(request)
            with self.lock:
                self.expire()
                row = self.db.execute("SELECT * FROM avas_jobs WHERE id=? AND device_id=?", (job_id, self.device_id)).fetchone()
                if not row:
                    raise HTTPException(404, "Arahan tidak ditemui.")
                return {"ok": True, "job": self.job_info(row)}

        @app.get("/api/remote/avas/audio/{upload_id}")
        async def avas_audio(upload_id: str, request: Request):
            self.authorize(request)
            if not ID.fullmatch(upload_id):
                raise HTTPException(404, "Audio tidak ditemui.")
            with self.lock:
                upload = self.upload_info(upload_id)
            path = self.audio_dir / (upload_id + ".wav")
            if not upload or not path.is_file() or path.is_symlink():
                raise HTTPException(404, "Audio tidak ditemui.")
            if path.stat().st_size != upload["bytes"] or hashlib.sha256(path.read_bytes()).hexdigest() != upload["sha256"]:
                raise HTTPException(409, "Integriti audio tidak sepadan.")
            return FileResponse(path, media_type="audio/wav", filename=upload_id + ".wav", headers={"Cache-Control": "private, no-store"})

    @staticmethod
    async def read_json(request):
        content = bytearray()
        async for chunk in request.stream():
            content.extend(chunk)
            if len(content) > 32768:
                raise HTTPException(413, "Permintaan terlalu besar.")
        try:
            return json.loads(content)
        except (ValueError, UnicodeError):
            raise HTTPException(422, "Permintaan JSON tidak sah.")


def install_avas_remote(app, authorize, audio_dir, database, device_id=None, now=time.time):
    controller = AvasRemote(authorize, audio_dir, database,
                            os.environ.get("REMOTE_AVAS_DEVICE_ID", "") if device_id is None else device_id, now)
    controller.register(app)
    return controller
