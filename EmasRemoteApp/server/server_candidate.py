# e.MAS smartwatch/phone web remote - proxy to ACO Tech API
# DIPERKEMAS: token sebenar + REQUEST-ID + serviceKey betul (RDL-OFF, RDU-ON, dll)
# Semua confirmed bekerja (Lock/UnLock/status) pada 19 Ogos 2026.
import os
import uuid
import json
import asyncio
import time
from fastapi import FastAPI, Request, HTTPException, UploadFile, File, Form
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware import Middleware
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.middleware.cors import CORSMiddleware
import httpx
import re

# CORS — benarkan request dari mana-mana origin (jam/phone/web)
app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

API_KEY = os.environ.get("API_KEY", "tukar-saya")
EMAS_TOKEN = os.environ.get("EMAS_TOKEN", "")  # fallback ke file token
BASE = "https://emas-app.acotech.my:8443/"
VIN = os.environ.get("EMAS_VIN", "LB3EH1SF4TX068700")
BASE_URL = os.environ.get("BASE_URL", "https://emas.43-159-63-29.nip.io").rstrip("/")

# Muat token dari file jika EMAS_TOKEN kosong
if not EMAS_TOKEN:
    try:
        import json
        with open(os.path.join(os.path.dirname(__file__), "emas_tokens.json"), "r") as f:
            EMAS_TOKEN = json.load(f).get("accessToken", "")
    except Exception:
        pass

app.mount("/assets", StaticFiles(directory="static/assets"), name="assets")
app.mount("/static", StaticFiles(directory="static"), name="static")


class NoCache(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        resp = await call_next(request)
        resp.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        return resp


app.add_middleware(NoCache)


def check_key(request: Request):
    if request.headers.get("X-API-Key") != API_KEY:
        raise HTTPException(401, "bad key")


TOKEN_FILE = os.path.join(os.path.dirname(__file__), "emas_tokens.json")

def load_token():
    """Muat accessToken dari file (sentiasa fresh)."""
    try:
        with open(TOKEN_FILE, "r") as f:
            return json.load(f).get("accessToken", "")
    except Exception:
        return ""

def get_refresh_token():
    try:
        with open(TOKEN_FILE, "r") as f:
            return json.load(f).get("refreshToken", "")
    except Exception:
        return ""

def save_tokens(new_tokens):
    """Simpan token baru ke file."""
    try:
        with open(TOKEN_FILE, "r") as f:
            t = json.load(f)
        t["accessToken"] = new_tokens.get("accessToken", t.get("accessToken", ""))
        t["refreshToken"] = new_tokens.get("refreshToken", t.get("refreshToken", ""))
        t["expireIn"] = new_tokens.get("expireIn", t.get("expireIn", 2592000))
        t["refreshExpireIn"] = new_tokens.get("refreshExpireIn", t.get("refreshExpireIn", 15552000))
        with open(TOKEN_FILE, "w") as f:
            json.dump(t, f, indent=2)
        return True
    except Exception:
        return False

# ==================== GLOBAL HTTP CLIENT (KEEP-ALIVE POOL) ====================
aco_client = None

def get_aco_client() -> httpx.AsyncClient:
    raise HTTPException(503, "Integrasi ACO dimatikan oleh pemilik.")

@app.on_event("shutdown")
async def shutdown_event():
    global aco_client
    if aco_client and not aco_client.is_closed:
        await aco_client.aclose()


async def refresh_token_if_needed():
    return None  # Keep the official Proton session untouched.

def aco_headers(token: str) -> dict:
    """Header penuh yang app e.MAS guna (dari VehicleHeaderProvider)."""
    return {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "CLIENT-ID": "android",
        "TENANT-ID": "e_Mas",
        "APP-NAME": "Proton_e.Mas",
        "X-EQUIPMENT-TYPE": "e_Mas",
        "X-EQUIPMENT-BRAND": "Xiaomi",
        "X-EQUIPMENT-MODEL": "2312DRA50C",
        "X-EQUIPMENT-OS-VERSION": "14",
        "X-AGENT-TYPE": "Android",
        "X-AGENT-VERSION": "34",
        "VERSION-NAME": "2.2.4",
        "VERSION-CODE": "179",
        "ACCEPT-LANGUAGE": "en",
        "User-Agent": "okhttp/4.12.0",
        "REQUEST-ID": uuid.uuid4().hex,
    }


@app.get("/")
def index():
    return FileResponse("static/index.html")


@app.get("/wear")
def wear():
    return FileResponse("static/wear/index.html")


@app.get("/debug.html")
def debug_page():
    return FileResponse("static/debug.html")


@app.get("/preview")
@app.get("/preview.html")
def preview_page():
    return FileResponse("static/preview.html")


@app.api_route("/download/eMAS_Remote_v2.apk", methods=["GET", "HEAD"])
@app.api_route("/download/apk", methods=["GET", "HEAD"])
def download_apk():
    apk_path = "static/apk/eMAS_Remote.apk"
    if not os.path.exists(apk_path):
        raise HTTPException(404, "APK not found")
    return FileResponse(apk_path, media_type="application/vnd.android.package-archive", filename="eMAS_Remote_v2.apk")


@app.post("/api/command/{service_key}")
async def command(service_key: str, request: Request):
    check_key(request)
    global car_trunk_pending_cmd

    # Intercept trunk commands for local OTA daemon motor control
    if service_key in ("RDU-TargetON", "trunk", "trunk_open", "trunk_close", "trunk_toggle"):
        if service_key == "trunk_open":
            car_trunk_pending_cmd = "open"
        elif service_key == "trunk_close":
            car_trunk_pending_cmd = "close"
        else:
            car_trunk_pending_cmd = "toggle"
        print(f"[TRUNK] Intercepted {service_key} -> Queued trunk_cmd='{car_trunk_pending_cmd}'")

        # If accessed via old VPS domain, automatically forward to new VPS
        host_header = request.headers.get("host", "")
        if "43-163-3-153" in host_header:
            try:
                async with httpx.AsyncClient(verify=False, timeout=3.0) as fwd_client:
                    await fwd_client.post(
                        f"https://emas.43-159-63-29.nip.io/api/command/{service_key}",
                        headers={"X-API-Key": API_KEY, "Content-Type": "application/json"},
                        json={}
                    )
                    print(f"[TRUNK] Auto-forwarded {service_key} to 43.159.63.29")
            except Exception as e:
                print(f"[TRUNK] Forward failed: {e}")
        resp_body = {
            "code": 200,
            "message": f"Trunk {car_trunk_pending_cmd} queued",
            "data": {"requestId": f"trunk_{int(time.time())}"}
        }
        return {
            "status": 200,
            "ok": True,
            "body": json.dumps(resp_body),
            "requestId": f"trunk_{int(time.time())}"
        }

    try:
        body = await request.json()
    except Exception:
        body = {}
    token = load_token()
    payload = {
        "serviceKey": service_key,
        "vin": VIN,
        "scheduledTime": body.get("scheduledTime"),
        "endTime": body.get("endTime"),
        "duration": body.get("duration"),
        "parameters": body.get("parameters"),  # null untuk command ringkas (Lock/UnLock)
        "timerData": body.get("timerData"),
    }
    client = get_aco_client()
    try:
        r = await client.post(BASE + "iot/function/vehicleControl", json=payload, headers=aco_headers(token))
        j = r.json()
        if j.get("code") == 401:
            new_token = await refresh_token_if_needed()
            if new_token:
                r = await client.post(BASE + "iot/function/vehicleControl", json=payload, headers=aco_headers(new_token))
                j = r.json()
        ok = j.get("code") == 200
        return {"status": 200 if ok else j.get("code", r.status_code), "body": r.text, "requestId": j.get("data", {}).get("requestId") if isinstance(j.get("data"), dict) else None}
    except Exception as e:
        return {"status": 500, "body": str(e)}


@app.get("/api/position")
async def position(request: Request):
    check_key(request)
    token = load_token()
    client = get_aco_client()
    try:
        r = await client.post(BASE + "iot/function/getVehiclePosition", json={"vin": VIN}, headers=aco_headers(token))
        j = r.json()
        if j.get("code") == 401:
            new_token = await refresh_token_if_needed()
            if new_token:
                r = await client.post(BASE + "iot/function/getVehiclePosition", json={"vin": VIN}, headers=aco_headers(new_token))
                j = r.json()
        return {"status": 200 if j.get("code") == 200 else j.get("code", r.status_code), "body": r.text}
    except Exception as e:
        return {"status": 500, "body": str(e)}


@app.get("/api/schedule")
async def schedule(request: Request):
    check_key(request)
    token = load_token()
    client = get_aco_client()
    try:
        r = await client.post(BASE + "iot/function/getBookingChargingSetting", json={"vin": VIN}, headers=aco_headers(token))
        j = r.json()
        if j.get("code") == 401:
            new_token = await refresh_token_if_needed()
            if new_token:
                r = await client.post(BASE + "iot/function/getBookingChargingSetting", json={"vin": VIN}, headers=aco_headers(new_token))
                j = r.json()
        return {"status": 200 if j.get("code") == 200 else j.get("code", r.status_code), "body": r.text}
    except Exception as e:
        return {"status": 500, "body": str(e)}


@app.get("/api/status")
async def status(request: Request):
    check_key(request)
    token = load_token()
    client = get_aco_client()
    try:
        r = await client.post(BASE + "iot/function/getVehicleStatus", json={"vin": VIN}, headers=aco_headers(token))
        j = r.json()
        if j.get("code") == 401:
            new_token = await refresh_token_if_needed()
            if new_token:
                r = await client.post(BASE + "iot/function/getVehicleStatus", json={"vin": VIN}, headers=aco_headers(new_token))
                j = r.json()
        if j.get("code") == 200:
            if isinstance(j.get("data"), dict):
                if car_trunk_state.get("last_updated", 0) > 0 and (time.time() - car_trunk_state["last_updated"]) < 60:
                    j["data"]["trunkOpenStatus"] = str(car_trunk_state["open"])
                if car_frunk_state.get("last_updated", 0) > 0 and (time.time() - car_frunk_state["last_updated"]) < 60:
                    j["data"]["frunkOpenStatus"] = str(car_frunk_state["open"])
            return {"status": 200, "body": json.dumps(j)}
        return {"status": j.get("code", r.status_code), "body": r.text}
    except Exception as e:
        return {"status": 500, "body": str(e)}


# ===== Direct Car Window & Pressure Relief APIs (Port 8888) =====
CAR_LOCAL_IP = os.environ.get("CAR_LOCAL_IP", "192.168.1.18")
CAR_LOCAL_PORT = int(os.environ.get("CAR_LOCAL_PORT", "8888"))

# ==================== LOCK SOUND MANAGER ====================
LOCK_DIR = os.path.join(os.path.dirname(__file__), "static", "locksounds")
LOCK_STATE_FILE = os.path.join(LOCK_DIR, "lock_state.json")
LOCK_TARGET_ON_DEVICE = "/sdcard/V3_Vehicle_Locked_Single.wav"

def load_lock_state():
    if os.path.exists(LOCK_STATE_FILE):
        try:
            with open(LOCK_STATE_FILE, "r") as f:
                return json.load(f)
        except Exception:
            pass
    return {"active": "original", "pending_deploy": None, "sounds": {}}

def save_lock_state(state):
    os.makedirs(LOCK_DIR, exist_ok=True)
    with open(LOCK_STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)

# ==================== UNLOCK / WELCOME SOUND MANAGER ====================
UNLOCK_STATE_FILE = os.path.join(LOCK_DIR, "unlock_state.json")

def load_unlock_state():
    if os.path.exists(UNLOCK_STATE_FILE):
        try:
            with open(UNLOCK_STATE_FILE, "r") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "active": "mario_coin",
        "enabled": True,
        "output": "both",
        "pending_deploy": None,
        "sounds": {
            "mario_coin": {"name": "Super Mario Coin", "file": "unlock_mario_coin.wav"},
            "mario_powerup": {"name": "Super Mario Power-Up", "file": "unlock_mario_powerup.wav"},
            "mario_1up": {"name": "Super Mario 1-Up", "file": "unlock_mario_1up.wav"},
            "cyber_chime": {"name": "Cyber Electric Chime", "file": "unlock_cyber_chime.wav"},
            "double_chirp": {"name": "Luxury Double Chirp", "file": "unlock_double_chirp.wav"},
            "omar_hana": {"name": "Omar & Hana", "file": "unlock_omar_hana.wav"},
        },
    }

def save_unlock_state(state):
    os.makedirs(LOCK_DIR, exist_ok=True)
    with open(UNLOCK_STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)

def queue_unlock_deploy(state):
    key = state.get("active")
    info = state.get("sounds", {}).get(key)
    if not key or not info:
        return None
    state["pending_deploy"] = {
        "deploy_id": f"unlock_{key}_{int(time.time() * 1000)}",
        "sound_key": key,
        "file": info["file"],
        "audio_url": f"{BASE_URL}/static/locksounds/{info['file']}?t={int(time.time())}",
        "enabled": bool(state.get("enabled", True)),
        "output": state.get("output", "both"),
    }
    return state["pending_deploy"]

@app.get("/api/unlocksound/list")
async def unlocksound_list():
    state = load_unlock_state()
    sounds = {}
    for key, info in state.get("sounds", {}).items():
        path = os.path.join(LOCK_DIR, info["file"])
        sounds[key] = {
            **info,
            "exists": os.path.exists(path),
            "url": f"/static/locksounds/{info['file']}?t={int(os.path.getmtime(path)) if os.path.exists(path) else 0}",
        }
    return {**state, "ok": True, "sounds": sounds}

@app.post("/api/unlocksound/set")
async def unlocksound_set(request: Request):
    data = await request.json()
    state = load_unlock_state()
    if "enabled" in data:
        state["enabled"] = bool(data["enabled"])
    if "output" in data:
        output = str(data["output"])
        if output not in ("both", "external", "cabin"):
            raise HTTPException(400, "Invalid output")
        state["output"] = output
    if "sound" in data:
        key = str(data["sound"])
        if key not in state.get("sounds", {}):
            raise HTTPException(400, f"Unknown sound: {key}")
        state["active"] = key
    deploy = queue_unlock_deploy(state)
    save_unlock_state(state)
    return {
        "ok": True,
        "queued": deploy is not None,
        "deploy_id": deploy.get("deploy_id") if deploy else None,
        "active": state.get("active"),
        "enabled": state.get("enabled"),
        "output": state.get("output"),
    }

@app.post("/api/unlocksound/upload")
async def unlocksound_upload(name: str = Form(...), file: UploadFile = File(...)):
    os.makedirs(LOCK_DIR, exist_ok=True)
    key = re.sub(r'[^a-z0-9_]', '_', name.lower().strip())[:20] or f"custom_{int(time.time())}"
    temp_path = os.path.join(LOCK_DIR, f"tmp_unlock_{uuid.uuid4().hex[:6]}")
    with open(temp_path, "wb") as f:
        f.write(await file.read())
    out_name = f"unlock_{key}.wav"
    out_path = os.path.join(LOCK_DIR, out_name)
    proc = await asyncio.create_subprocess_exec(
        "ffmpeg", "-y", "-i", temp_path,
        "-t", "4.0", "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le",
        "-af", "afade=t=out:st=3.6:d=0.4,loudnorm=I=-16:TP=-1.5", out_path,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    _, stderr = await proc.communicate()
    try:
        os.remove(temp_path)
    except Exception:
        pass
    if proc.returncode != 0:
        raise HTTPException(500, f"FFmpeg failed: {stderr.decode(errors='ignore')[-200:]}")
    state = load_unlock_state()
    state.setdefault("sounds", {})[key] = {"name": name[:40], "file": out_name}
    save_unlock_state(state)
    return {"ok": True, "key": key, "url": f"/static/locksounds/{out_name}"}

@app.get("/api/locksound/list")
async def locksound_list():
    st = load_lock_state()
    # semak fail wujud
    sounds = {}
    for key, info in st.get("sounds", {}).items():
        path = os.path.join(LOCK_DIR, info["file"])
        sounds[key] = {
            **info,
            "exists": os.path.exists(path),
            "url": f"/static/locksounds/{info['file']}?t={int(os.path.getmtime(path)) if os.path.exists(path) else 0}"
        }
    return {"ok": True, "active": st.get("active"), "pending_deploy": st.get("pending_deploy"), "sounds": sounds}

@app.post("/api/locksound/set")
async def locksound_set(request: Request):
    data = await request.json()
    key = str(data.get("sound", ""))
    st = load_lock_state()
    if key not in st.get("sounds", {}):
        raise HTTPException(400, f"Unknown sound: {key}")
    st["pending_deploy"] = {
        "deploy_id": f"lock_{key}_{int(time.time())}",
        "sound_key": key,
        "file": st["sounds"][key]["file"],
        "target": LOCK_TARGET_ON_DEVICE,
        "audio_url": f"{BASE_URL}/static/locksounds/{st['sounds'][key]['file']}?t={int(time.time())}"
    }
    st["active"] = key
    save_lock_state(st)
    return {"ok": True, "deploy_id": st["pending_deploy"]["deploy_id"], "active": key}

@app.post("/api/locksound/upload")
async def locksound_upload(name: str = Form(...), file: UploadFile = File(...)):
    os.makedirs(LOCK_DIR, exist_ok=True)
    key = re.sub(r'[^a-z0-9_]', '_', name.lower().strip())[:20] or f"custom_{int(time.time())}"
    temp_path = os.path.join(LOCK_DIR, f"tmp_{uuid.uuid4().hex[:6]}")
    with open(temp_path, "wb") as f:
        f.write(await file.read())
    out_wav = os.path.join(LOCK_DIR, f"lock_{key}.wav")
    cmd = [
        "ffmpeg", "-y", "-i", temp_path,
        "-t", "3.0", "-ar", "48000", "-ac", "2", "-c:a", "pcm_s16le",
        "-af", "afade=t=out:st=2.6:d=0.4,loudnorm=I=-16:TP=-1.5",
        out_wav
    ]
    proc = await asyncio.create_subprocess_exec(*cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    _, stderr = await proc.communicate()
    try:
        os.remove(temp_path)
    except Exception:
        pass
    if proc.returncode != 0:
        raise HTTPException(500, f"FFmpeg failed: {stderr.decode(errors='ignore')[-200:]}")
    st = load_lock_state()
    st.setdefault("sounds", {})[key] = {"name": name[:40], "file": f"lock_{key}.wav"}
    save_lock_state(st)
    return {"ok": True, "key": key, "url": f"/static/locksounds/lock_{key}.wav"}

# ==================== MEGAFON / EXTERIOR SPEAKER ====================
MEGAFON_DIR = os.path.join(os.path.dirname(__file__), "static", "megafon")
os.makedirs(MEGAFON_DIR, exist_ok=True)
car_megaphone_pending = None
car_megaphone_switch = {
    "enabled": False,
    "updated_at": 0,
    "expires_at": 0
}

@app.get("/api/megafon/switch")
async def megafon_switch_get():
    return {"ok": True, **REMOTE.switch()}

@app.post("/api/megafon/switch")
async def megafon_switch_set(request: Request):
    return await REMOTE.set_switch(request)

@app.get("/api/megafon/status")
async def megafon_status(request: Request):
    REMOTE.authorize(request)
    state = REMOTE.status()
    return {"ok": True, "pending": REMOTE.pending(), "switch": state["megaphone"]["enabled"], "remaining_sec": state["megaphone"]["remaining_sec"]}

@app.post("/api/megafon/speak")
async def megafon_speak(request: Request):
    return await REMOTE.speak(request)

@app.post("/api/megafon/upload")
async def megafon_upload(request: Request, file: UploadFile = File(...)):
    return await REMOTE.upload(request, file)

# ==================== AVAS SOUND STUDIO ====================
AVAS_DIR = os.path.join(os.path.dirname(__file__), "static", "avas")
AVAS_STATE_FILE = os.path.join(AVAS_DIR, "avas_state.json")

def make_crc16_table():
    tbl = []
    for i in range(256):
        curr = (i << 8) & 0xFFFF
        for _ in range(8):
            if curr & 0x8000:
                curr = ((curr << 1) ^ 0x1021) & 0xFFFF
            else:
                curr = (curr << 1) & 0xFFFF
        tbl.append(curr)
    return tbl

CRC_TABLE_AVAS = make_crc16_table()

def crc16_avas(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        if b == 0x0D:
            continue
        crc = ((crc << 8) & 0xFF00) ^ CRC_TABLE_AVAS[(b ^ (crc >> 8)) & 0xFF]
    return crc

EXC_XOR70 = {0x0D, 0x0A, 0x70, 0x7D}

def encode_avas_tuning(sound_mode: int, track_path: str, volumes: list, pitches: list, mute_chime: bool = True) -> bytes:
    vol_str = ",".join(str(int(v)) for v in volumes)
    pitch_str = ",".join(str(int(p)) for p in pitches)
    chime_vol = "0,0,0,0,0,0,0" if mute_chime else "0,2,5,6,7,2,0"
    
    body = (
        f"Sound_Mode:{sound_mode}\r\n"
        f"Pedal:0\r\n"
        f"Break:0\r\n"
        f"Volume_type:31,31,31\r\n"
        f"Track:/vendor/etc/avas/sound_type_{sound_mode}/bDM_01_01.wav\r\n"
        f"Mixer_Volume:10,10,10\r\n"
        f"Pitch:100,108,116,124,132,140,148\r\n"
        f"Volume:{chime_vol}\r\n"
        f"Track:{track_path}\r\n"
        f"Mixer_Volume:10,10,10\r\n"
        f"Pitch:{pitch_str}\r\n"
        f"Volume:{vol_str}\r\n"
        f"[stop]\r\n"
    ).encode("latin1")
    
    crc = crc16_avas(body)
    header = f"Encryption_Code:{crc}\r\n".encode("latin1")
    full = header + body
    
    out = bytearray()
    for b in full:
        if b in EXC_XOR70:
            out.append(b)
        else:
            out.append(b ^ 0x70)
    return bytes(out)

def load_avas_state():
    if os.path.exists(AVAS_STATE_FILE):
        try:
            with open(AVAS_STATE_FILE, "r") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "active_slot": 3,
        "slots": {
            "1": {
                "name": "Yinhe Yinfu (Galaxy)",
                "custom_name": "Paddle Pop",
                "audio_file": "slot1.wav",
                "has_custom_audio": True,
                "mute_chime": True,
                "speeds": [0, 5, 10, 15, 20, 25, 30],
                "volume": [0, 2, 5, 6, 7, 2, 0],
                "pitch": [100, 100, 100, 100, 100, 100, 100]
            },
            "2": {
                "name": "Xingdong (Star Motion)",
                "custom_name": "Dodge V8 Rumble",
                "audio_file": "slot2.wav",
                "has_custom_audio": True,
                "mute_chime": True,
                "speeds": [0, 5, 10, 15, 20, 25, 30],
                "volume": [0, 8, 10, 13, 16, 7, 0],
                "pitch": [100, 100, 100, 100, 100, 100, 100]
            },
            "3": {
                "name": "Pixel (Sport)",
                "custom_name": "Super Mario Bros Theme",
                "audio_file": "slot3.wav",
                "has_custom_audio": True,
                "mute_chime": True,
                "speeds": [0, 5, 10, 15, 20, 25, 30],
                "volume": [0, 4, 5, 6, 7, 3, 0],
                "pitch": [100, 102, 105, 108, 112, 116, 120]
            }
        },
        "pending_deploy": None
    }

def save_avas_state(state):
    os.makedirs(AVAS_DIR, exist_ok=True)
    with open(AVAS_STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)

@app.get("/api/avas/state")
async def avas_get_state():
    return load_avas_state()

@app.post("/api/avas/upload")
async def avas_upload(slot: int = Form(...), file: UploadFile = File(...)):
    if slot not in [1, 2, 3]:
        raise HTTPException(400, "Invalid slot")
    os.makedirs(AVAS_DIR, exist_ok=True)
    temp_path = os.path.join(AVAS_DIR, f"tmp_{slot}_{uuid.uuid4().hex[:6]}")
    with open(temp_path, "wb") as f:
        f.write(await file.read())
        
    out_wav = os.path.join(AVAS_DIR, f"slot{slot}.wav")
    
    cmd = [
        "ffmpeg", "-y", "-i", temp_path,
        "-ar", "48000", "-ac", "1", "-c:a", "pcm_s16le",
        "-af", "afade=t=in:ss=0:d=0.05",
        out_wav
    ]
    proc = await asyncio.create_subprocess_exec(*cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    stdout, stderr = await proc.communicate()
    try:
        os.remove(temp_path)
    except Exception:
        pass
        
    if proc.returncode != 0:
        raise HTTPException(500, f"FFmpeg failed: {stderr.decode(errors='ignore')[-200:]}")
        
    st = load_avas_state()
    st["slots"][str(slot)]["has_custom_audio"] = True
    st["slots"][str(slot)]["custom_name"] = file.filename
    st["slots"][str(slot)]["audio_file"] = f"slot{slot}.wav"
    save_avas_state(st)
    
    return {
        "ok": True,
        "slot": slot,
        "filename": file.filename,
        "audio_url": f"/static/avas/slot{slot}.wav?t={int(time.time())}"
    }

@app.post("/api/avas/save")
async def avas_save_config(request: Request):
    data = await request.json()
    slot = int(data.get("slot", 3))
    if slot not in [1, 2, 3]:
        raise HTTPException(400, "Invalid slot")
        
    st = load_avas_state()
    s_info = st["slots"][str(slot)]
    
    if "volume" in data:
        s_info["volume"] = [int(v) for v in data["volume"][:7]]
    if "pitch" in data:
        s_info["pitch"] = [int(p) for p in data["pitch"][:7]]
    if "mute_chime" in data:
        s_info["mute_chime"] = bool(data["mute_chime"])
    if "custom_name" in data:
        s_info["custom_name"] = str(data["custom_name"])
        
    track_names = {
        1: "yinheyinfu.wav",
        2: "space.wav",
        3: "pixel.wav"
    }
    
    track_path = f"/vendor/etc/avas/sound_type_{slot}/{track_names[slot]}"
    encoded = encode_avas_tuning(
        sound_mode=slot,
        track_path=track_path,
        volumes=s_info["volume"],
        pitches=s_info["pitch"],
        mute_chime=s_info["mute_chime"]
    )
    
    tuning_file = os.path.join(AVAS_DIR, f"tuning{slot}.txt")
    with open(tuning_file, "wb") as f:
        f.write(encoded)
        
    deploy_id = f"avas_{slot}_{int(time.time())}"
    st["pending_deploy"] = {
        "deploy_id": deploy_id,
        "slot": slot,
        "audio_url": f"{BASE_URL}/static/avas/slot{slot}.wav?t={int(time.time())}",
        "tuning_url": f"{BASE_URL}/static/avas/tuning{slot}.txt?t={int(time.time())}",
        "mute_chime": s_info["mute_chime"],
        "track_name": track_names[slot]
    }
    st["active_slot"] = slot
    save_avas_state(st)
    
    return {"ok": True, "deploy_id": deploy_id, "state": st}

car_window_state = {
    "enabled": True,
    "crack_percent": 7,
    "ventilation_mode": False,
    "ventilation_remaining_sec": 0,
    "close_requested": False,
    "last_seen": 0,
    "windows": {"fl": 0, "fr": 0, "rl": 0, "rr": 0},
    "doors": {"fl": 1, "fr": 1, "rl": 1, "rr": 1, "trunk": 0},
}

car_trunk_pending_cmd = None
car_trunk_state = {
    "open": 0,
    "last_updated": 0
}
car_frunk_pending_cmd = None
car_frunk_state = {
    "open": 0,
    "last_updated": 0
}
car_launch_app_pending_cmd = None

car_energy_state = {}  # No example readings in production.

@app.post("/api/car/sync")
async def car_sync(request: Request):
    global car_window_state, car_energy_state, car_trunk_pending_cmd, car_trunk_state, car_frunk_pending_cmd, car_frunk_state
    try:
        data = await request.json()
    except Exception:
        raise HTTPException(400, "JSON sync tidak sah.")
    if not isinstance(data, dict):
        raise HTTPException(400, "Sync mesti objek JSON.")
    car_window_state["last_seen"] = int(time.time())
    if "windows" in data:
        car_window_state["windows"] = data["windows"]
    if "doors" in data:
        car_window_state["doors"] = data["doors"]
        if isinstance(data["doors"], dict):
            if "trunk" in data["doors"]:
                car_trunk_state["open"] = int(data["doors"]["trunk"])
                car_trunk_state["last_updated"] = int(time.time())
            if "frunk" in data["doors"]:
                car_frunk_state["open"] = int(data["doors"]["frunk"])
                car_frunk_state["last_updated"] = int(time.time())
    if "ventilation_mode" in data and not car_window_state.get("close_requested"):
        car_window_state["ventilation_mode"] = data["ventilation_mode"]
    if "ventilation_remaining_sec" in data:
        car_window_state["ventilation_remaining_sec"] = data["ventilation_remaining_sec"]
    
    if "energy" in data and isinstance(data["energy"], dict):
        car_energy_state.update(data["energy"])
        car_energy_state["updated_at"] = int(time.time())

    if "avas_applied" in data:
        st = load_avas_state()
        if st.get("pending_deploy") and st["pending_deploy"].get("deploy_id") == data["avas_applied"]:
            st["pending_deploy"] = None
            save_avas_state(st)

    if "lock_applied" in data:
        st = load_lock_state()
        if st.get("pending_deploy") and st["pending_deploy"].get("deploy_id") == data["lock_applied"]:
            st["pending_deploy"] = None
            save_lock_state(st)

    if "unlock_applied" in data:
        st = load_unlock_state()
        if st.get("pending_deploy") and st["pending_deploy"].get("deploy_id") == data["unlock_applied"]:
            st["pending_deploy"] = None
            save_unlock_state(st)

    trunk_cmd_to_send = None
    if car_trunk_pending_cmd:
        trunk_cmd_to_send = car_trunk_pending_cmd
        car_trunk_pending_cmd = None
        print(f"[SYNC] Dispatched trunk_cmd='{trunk_cmd_to_send}' to car daemon")

    frunk_cmd_to_send = None
    if car_frunk_pending_cmd:
        frunk_cmd_to_send = car_frunk_pending_cmd
        car_frunk_pending_cmd = None
        print(f"[SYNC] Dispatched frunk_cmd='{frunk_cmd_to_send}' to car daemon")

    global car_launch_app_pending_cmd
    launch_app_to_send = None
    if car_launch_app_pending_cmd:
        launch_app_to_send = car_launch_app_pending_cmd
        car_launch_app_pending_cmd = None
        print(f"[SYNC] Dispatched launch_app_cmd='{launch_app_to_send}' to car daemon")

    mega_to_send = REMOTE.sync(data)

    avas_st = load_avas_state()
    lock_st = load_lock_state()
    unlock_st = load_unlock_state()
    now_sync = int(time.time())
    remote_switch = REMOTE.switch()
    poll_interval = 1.5 if remote_switch["enabled"] else 3.0

    resp_obj = {
        "ok": True,
        "enabled": car_window_state["enabled"],
        "crack_percent": car_window_state["crack_percent"],
        "ventilation_mode": car_window_state["ventilation_mode"],
        "close_requested": car_window_state["close_requested"],
        "avas_deploy": avas_st.get("pending_deploy"),
        "lock_deploy": lock_st.get("pending_deploy"),
        "unlock_deploy": unlock_st.get("pending_deploy"),
        "megaphone_switch": remote_switch["enabled"],
        "poll_interval_sec": poll_interval
    }
    if trunk_cmd_to_send:
        resp_obj["trunk_cmd"] = trunk_cmd_to_send
    if frunk_cmd_to_send:
        resp_obj["frunk_cmd"] = frunk_cmd_to_send
    if launch_app_to_send:
        resp_obj["launch_app_cmd"] = launch_app_to_send
    if mega_to_send:
        resp_obj["megaphone"] = mega_to_send
    return resp_obj

@app.get("/api/car/energy")
async def car_energy_get():
    is_fresh = (time.time() - car_energy_state.get("updated_at", 0)) < 15
    return {
        "ok": True,
        "online": is_fresh,
        "energy": car_energy_state
    }

@app.post("/api/car/energy")
async def car_energy_set(request: Request):
    global car_energy_state
    try:
        data = await request.json()
    except Exception:
        data = {}
    if data:
        car_energy_state.update(data)
        car_energy_state["updated_at"] = int(time.time())
    return {"ok": True, "energy": car_energy_state}

@app.get("/api/car/sync")
async def car_sync_get():
    avas_st = load_avas_state()
    lock_st = load_lock_state()
    unlock_st = load_unlock_state()
    return {
        "ok": True,
        "enabled": car_window_state["enabled"],
        "crack_percent": car_window_state["crack_percent"],
        "ventilation_mode": car_window_state["ventilation_mode"],
        "close_requested": car_window_state["close_requested"],
        "avas_deploy": avas_st.get("pending_deploy"),
        "lock_deploy": lock_st.get("pending_deploy"),
        "unlock_deploy": unlock_st.get("pending_deploy"),
        "trunk": car_trunk_state,
        "trunk_pending": car_trunk_pending_cmd
    }

@app.post("/api/car/trunk/{action}")
async def car_trunk_action(action: str):
    global car_trunk_pending_cmd
    act = action.lower()
    if act not in ("open", "close", "toggle"):
        raise HTTPException(400, "Invalid action. Use open, close, or toggle")
    car_trunk_pending_cmd = act
    print(f"[TRUNK] REST API queued trunk_cmd='{act}'")
    return {"ok": True, "action": act, "queued": True}

@app.get("/api/car/trunk/status")
async def car_trunk_status():
    return {
        "ok": True,
        "trunk": car_trunk_state,
        "pending_cmd": car_trunk_pending_cmd
    }

@app.post("/api/car/frunk/{action}")
async def car_frunk_action(action: str):
    global car_frunk_pending_cmd
    act = action.lower()
    if act not in ("unlock", "open"):
        raise HTTPException(400, "Invalid action. Use unlock or open")
    car_frunk_pending_cmd = act
    print(f"[FRUNK] REST API queued frunk_cmd='{act}'")
    return {"ok": True, "action": act, "queued": True}

@app.get("/api/car/frunk/status")
async def car_frunk_status():
    return {
        "ok": True,
        "frunk": car_frunk_state,
        "pending_cmd": car_frunk_pending_cmd
    }

@app.post("/api/car/app/launch/{app_name}")
async def car_app_launch(app_name: str):
    global car_launch_app_pending_cmd
    act = app_name.lower().strip()
    car_launch_app_pending_cmd = act
    print(f"[APP-LAUNCH] REST API queued launch_app_cmd='{act}'")
    return {"ok": True, "app": act, "queued": True}

@app.get("/api/car/app/launch/status")
async def car_app_launch_status():
    return {
        "ok": True,
        "pending_cmd": car_launch_app_pending_cmd
    }

@app.get("/api/car/window/status")
async def car_window_status():
    try:
        async with httpx.AsyncClient(timeout=1.5) as client:
            r = await client.get(f"http://{CAR_LOCAL_IP}:{CAR_LOCAL_PORT}/api/status")
            if r.status_code == 200:
                j = r.json()
                car_window_state.update(j)
                return j
    except Exception:
        pass
    return {
        "ok": True,
        "enabled": car_window_state["enabled"],
        "crack_percent": car_window_state["crack_percent"],
        "ventilation_mode": car_window_state["ventilation_mode"],
        "ventilation_remaining_sec": car_window_state["ventilation_remaining_sec"],
        "windows": car_window_state["windows"],
        "doors": car_window_state["doors"],
        "online": (time.time() - car_window_state.get("last_seen", 0)) < 15,
    }

@app.post("/api/car/window/set")
async def car_window_set(request: Request):
    global car_window_state
    try:
        data = await request.json()
    except Exception:
        data = {}
    if "enabled" in data:
        car_window_state["enabled"] = bool(data["enabled"])
    if "crack_percent" in data:
        car_window_state["crack_percent"] = int(data["crack_percent"])
    direct = False
    try:
        async with httpx.AsyncClient(timeout=1.5) as client:
            response = await client.post(f"http://{CAR_LOCAL_IP}:{CAR_LOCAL_PORT}/api/set", json=data)
            direct = response.status_code == 200
    except Exception:
        pass
    return {"ok": True, "queued": True, "direct": direct,
            "enabled": car_window_state["enabled"], "crack_percent": car_window_state["crack_percent"]}

@app.post("/api/car/window/ventilate")
async def car_window_ventilate(request: Request):
    global car_window_state
    try:
        data = await request.json()
    except Exception:
        data = {}
    car_window_state["ventilation_mode"] = True
    car_window_state["close_requested"] = False
    direct = False
    try:
        async with httpx.AsyncClient(timeout=1.5) as client:
            response = await client.post(f"http://{CAR_LOCAL_IP}:{CAR_LOCAL_PORT}/api/ventilate", json=data)
            direct = response.status_code == 200
    except Exception:
        pass
    return {"ok": True, "queued": True, "direct": direct, "ventilation_mode": True}

@app.post("/api/car/window/close")
async def car_window_close():
    global car_window_state
    car_window_state["ventilation_mode"] = False
    car_window_state["close_requested"] = True
    direct = False
    try:
        async with httpx.AsyncClient(timeout=1.5) as client:
            response = await client.post(f"http://{CAR_LOCAL_IP}:{CAR_LOCAL_PORT}/api/close")
            direct = response.status_code == 200
    except Exception:
        pass
    return {"ok": True, "queued": True, "direct": direct, "close_requested": True}


# ===== 30-Minute AC Cool Cabin Keepalive =====
ac_task = None
ac_session = {
    "active": False,
    "start_time": 0,
    "duration_sec": 1800,
    "temp": 20,
}

async def ac_keepalive_worker():
    global ac_session
    print("[AC-30] Started 30-minute AC keepalive worker")
    try:
        while True:
            elapsed = time.time() - ac_session["start_time"]
            remaining = ac_session["duration_sec"] - elapsed
            if remaining <= 0 or not ac_session["active"]:
                break
            
            token = load_token()
            temp_str = str(ac_session.get("temp", 20))
            payload = {
                "serviceKey": "RCE-ON",
                "vin": VIN,
                "duration": 1800,
                "parameters": [{"key": "rce.temp", "stringVal": temp_str}]
            }
            try:
                async with httpx.AsyncClient(verify=False, timeout=30) as client:
                    r = await client.post(BASE + "iot/function/vehicleControl", json=payload, headers=aco_headers(token))
                    print(f"[AC-30] Sent keepalive RCE-ON (elapsed: {int(elapsed)}s, rem: {int(remaining)}s): {r.status_code}")
            except Exception as e:
                print(f"[AC-30] Error sending RCE-ON: {e}")
            
            sleep_time = min(480, max(10, remaining))
            await asyncio.sleep(sleep_time)

        if ac_session["active"]:
            print("[AC-30] 30 minutes completed! Sending notification...")
            try:
                await send_push("❄️ e.MAS 5 Sejuk!", "Pre-climate 30 minit selesai. Kabin dah sejuk nyaman!")
            except Exception:
                pass
    finally:
        ac_session["active"] = False

@app.get("/api/climate/cool30/status")
async def cool30_status():
    if ac_session["active"]:
        elapsed = time.time() - ac_session["start_time"]
        remaining = max(0, int(ac_session["duration_sec"] - elapsed))
        return {"active": True, "remaining_sec": remaining, "temp": ac_session.get("temp", 20)}
    return {"active": False, "remaining_sec": 0}

@app.post("/api/climate/cool30/start")
async def cool30_start(request: Request):
    global ac_task, ac_session
    try:
        data = await request.json()
    except Exception:
        data = {}
    temp = data.get("temp", 20)
    ac_session = {
        "active": True,
        "start_time": time.time(),
        "duration_sec": 1800,
        "temp": temp,
    }
    if ac_task and not ac_task.done():
        ac_task.cancel()
    ac_task = asyncio.create_task(ac_keepalive_worker())
    return {"ok": True, "msg": "Mod sejukkan 30 minit dimulakan", "duration": 1800}

@app.post("/api/climate/cool30/stop")
async def cool30_stop():
    global ac_task, ac_session
    ac_session["active"] = False
    if ac_task and not ac_task.done():
        ac_task.cancel()
    token = load_token()
    payload = {"serviceKey": "RCE-OFF", "vin": VIN}
    try:
        async with httpx.AsyncClient(verify=False, timeout=30) as client:
            await client.post(BASE + "iot/function/vehicleControl", json=payload, headers=aco_headers(token))
    except Exception:
        pass
    return {"ok": True, "msg": "Mod sejukkan dimatikan"}


# ===== PWA: manifest + service worker =====
@app.get("/manifest.json")
def manifest():
    return FileResponse("static/manifest.json", media_type="application/manifest+json")

@app.get("/sw.js")
def sw():
    return FileResponse("static/sw.js", media_type="application/javascript")


# ===== Push Notification (VAPID) =====
VAPID_PUBLIC = os.environ.get("VAPID_PUBLIC", "")
VAPID_PRIVATE = os.environ.get("VAPID_PRIVATE", "")
PUSH_SUBS_FILE = os.path.join(os.path.dirname(__file__), "push_subs.json")

def load_subs() -> list:
    try:
        with open(PUSH_SUBS_FILE, "r") as f:
            return json.load(f)
    except Exception:
        return []

def save_subs(subs: list):
    with open(PUSH_SUBS_FILE, "w") as f:
        json.dump(subs, f)

@app.post("/api/push/subscribe")
async def push_subscribe(request: Request):
    check_key(request)
    sub = await request.json()
    subs = load_subs()
    # elak duplicate endpoint
    subs = [s for s in subs if s.get("endpoint") != sub.get("endpoint")]
    subs.append(sub)
    save_subs(subs)
    return {"ok": True, "count": len(subs)}

@app.post("/api/push/unsubscribe")
async def push_unsubscribe(request: Request):
    check_key(request)
    body = await request.json()
    subs = load_subs()
    subs = [s for s in subs if s.get("endpoint") != body.get("endpoint")]
    save_subs(subs)
    return {"ok": True, "count": len(subs)}

@app.get("/api/push/public-key")
async def push_public_key(request: Request):
    """Bagi VAPID public key kepada UI untuk subscribe."""
    check_key(request)
    return {"publicKey": VAPID_PUBLIC}


# ===== Background poller: check kereta setiap 5 minit, push bila ada perubahan =====
last_state = {}

# ===== Geofence anti-curi =====
GEOFENCE_FILE = os.path.join(os.path.dirname(__file__), "geofence.json")

def load_geofence() -> dict:
    try:
        with open(GEOFENCE_FILE, "r") as f:
            return json.load(f)
    except Exception:
        return {}

def save_geofence(g: dict):
    with open(GEOFENCE_FILE, "w") as f:
        json.dump(g, f, indent=2)

@app.get("/api/geofence")
async def geofence_get(request: Request):
    check_key(request)
    return load_geofence()

@app.post("/api/geofence")
async def geofence_set(request: Request):
    """Body: { lat, lng, radius, armed } — radius meter, armed = mod pengawal ON/OFF."""
    check_key(request)
    body = await request.json()
    g = load_geofence()
    if "lat" in body: g["lat"] = float(body["lat"])
    if "lng" in body: g["lng"] = float(body["lng"])
    if "radius" in body: g["radius"] = max(50, int(body["radius"]))
    if "armed" in body:
        was_armed = g.get("armed", False)
        g["armed"] = bool(body["armed"])
        # bila baru ON, reset supaya tak alert terus bila kereta luar fence
        if body["armed"] and not was_armed:
            g["outside_since"] = None
    g["updated"] = int(time.time())
    save_geofence(g)
    return {"ok": True, "geofence": g}

def geo_distance_m(lat1, lng1, lat2, lng2):
    """Jarak meter antara dua koordinat (haversine betul)."""
    import math
    R = 6371000
    p1 = lat1 * math.pi / 180
    p2 = lat2 * math.pi / 180
    dlat = (lat2 - lat1) * math.pi / 180
    dlng = (lng2 - lng1) * math.pi / 180
    a = math.sin(dlat/2)**2 + math.cos(p1) * math.cos(p2) * math.sin(dlng/2)**2
    return 2 * R * math.asin(math.sqrt(a))

async def send_push(title: str, body: str):
    """Hantar push notification ke semua subscriber."""
    if not VAPID_PRIVATE:
        return
    from pywebpush import webpush, WebPushException
    subs = load_subs()
    valid = []
    for sub in subs:
        try:
            webpush(
                subscription_info=sub,
                data=json.dumps({"title": title, "body": body, "tag": "emas5"}),
                vapid_private_key=VAPID_PRIVATE,
                vapid_public_key=VAPID_PUBLIC,
                ttl=86400,
            )
            valid.append(sub)
        except WebPushException as e:
            # 404/410 = subscription expired — buang
            if e.response and e.response.status_code in (404, 410):
                continue
            valid.append(sub)
        except Exception:
            valid.append(sub)
    if len(valid) != len(subs):
        save_subs(valid)

async def poll_vehicle():
    """Poll kereta setiap 5 minit, banding perubahan, push notification.
    Mod Pengawal (geofence): poll lebih kerap (2 minit) bila armed."""
    global last_state
    while True:
        gf = load_geofence()
        armed = bool(gf.get("armed")) and "lat" in gf and "lng" in gf
        await asyncio.sleep(120 if armed else 300)  # 2 min armed / 5 min biasa
        try:
            async with httpx.AsyncClient(verify=False, timeout=30) as client:
                r = await client.post(
                    BASE + "iot/function/getVehicleStatus",
                    json={"vin": VIN},
                    headers=aco_headers(load_token()),
                )
                j = r.json()
                if j.get("code") != 200:
                    continue
                d = j.get("data", {})

                # ===== Geofence guard =====
                if armed:
                    try:
                        lat = float(d.get("latitude"))
                        lng = float(d.get("longitude"))
                        trusted = str(d.get("posCanBeTrusted", "true")) != "false"
                        if trusted and not (lat == 0 and lng == 0):
                            dist = geo_distance_m(gf["lat"], gf["lng"], lat, lng)
                            radius = int(gf.get("radius", 200))
                            if dist > radius:
                                if not gf.get("outside_since"):
                                    gf["outside_since"] = int(time.time())
                                    save_geofence(gf)
                                    km = dist / 1000
                                    dist_txt = f"{km:.1f} km" if dist > 999 else f"{int(dist)} m"
                                    await send_push(
                                        "🚨 PENGAWAL: Kereta bergerak!",
                                        f"Kereta keluar dari zon selamat ({dist_txt} dari rumah). "
                                        f"Lokasi: https://maps.google.com/?q={lat},{lng}",
                                    )
                                # reminder setiap 30 minit bila masih luar
                                elif int(time.time()) - int(gf.get("outside_since", 0)) > 1800:
                                    gf["outside_since"] = int(time.time())
                                    save_geofence(gf)
                                    await send_push(
                                        "🚨 PENGAWAL: Masih di luar zon",
                                        f"Lokasi: https://maps.google.com/?q={lat},{lng}",
                                    )
                            else:
                                # dah balik dalam zon
                                if gf.get("outside_since"):
                                    gf["outside_since"] = None
                                    save_geofence(gf)
                                    await send_push("✅ Pengawal", "Kereta dah kembali ke zon selamat")
                    except Exception:
                        pass

                now_state = {
                    "soc": d.get("powerBatteryLevel"),
                    "charging": d.get("chargeStatus"),
                    "lock": d.get("centralLockingStatus"),
                    "ac": d.get("preClimateActive"),
                }
                if not last_state:
                    last_state = now_state
                    continue
                changes = []
                if now_state.get("charging") != last_state.get("charging"):
                    changes.append("Cas " + ("mula" if now_state.get("charging") == "1" else "berhenti"))
                if now_state.get("lock") != last_state.get("lock"):
                    changes.append("Kunci bertukar: " + str(now_state.get("lock")))
                if now_state.get("ac") != last_state.get("ac"):
                    changes.append("AC " + ("ON" if now_state.get("ac") else "OFF"))
                soc = now_state.get("soc")
                if soc and last_state.get("soc") and int(soc) >= 100 and int(last_state.get("soc", 0)) < 100:
                    changes.append("Bateri PENUH! 🔋100%")
                if soc and int(soc) <= 20 and (not last_state.get("soc") or int(last_state.get("soc", 100)) > 20):
                    changes.append("Bateri RENDAH! ⚠️ " + str(soc) + "%")
                if changes:
                    await send_push("e.MAS 5 Update", " | ".join(changes))
                last_state = now_state
        except Exception:
            pass

@app.on_event("startup")
async def start_poller():
    asyncio.create_task(poll_vehicle())

# === eMAS Remote v2 (ACO remains disabled) ===
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
from fastapi import Request, UploadFile, File, HTTPException


class RemoteController:
    MAX_BYTES = 2 * 1024 * 1024
    MAX_QUEUE = 3
    PRESETS = {"alihkan": "Tolong alihkan", "salam": "Assalamualaikum",
               "tepi": "Beri laluan", "terima_kasih": "Terima kasih"}

    def __init__(self, audio_dir, base_url, database, authorize, energy_provider, clock=time.time):
        self.audio_dir = Path(audio_dir)
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        self.base_url = base_url.rstrip("/")
        self.authorize = authorize
        self.energy_provider = energy_provider
        self.clock = clock
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
            self.db.execute("DELETE FROM jobs WHERE id NOT IN (SELECT id FROM jobs ORDER BY created_at DESC LIMIT 200) AND status NOT IN ('queued','claimed')")

    def online(self):
        return self.last_seen > 0 and 0 <= self.now() - self.last_seen < 15

    def ready(self):
        if not self.switch()["enabled"]:
            raise HTTPException(409, "Aktifkan megafon dahulu.")
        if not self.online():
            raise HTTPException(503, "IHU tiada heartbeat. Kereta mungkin tidur atau tiada internet.")

    def valid_wav(self, path):
        try:
            with wave.open(str(path), "rb") as audio:
                if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, 48000, "NONE"):
                    return False
                count = audio.getnframes()
                return 0 < count <= 48000 * 20 and len(audio.readframes(count)) == count * 2
        except (OSError, EOFError, wave.Error):
            return False

    def available_presets(self):
        return [{"id": key, "label": label} for key, label in self.PRESETS.items()
                if self.valid_wav(self.audio_dir / (key + ".wav"))]

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
            self.db.execute("INSERT INTO jobs VALUES (:id,:text,:audio_url,:status,:created_at,:expires_at,:accepted_at,:request_key,:fingerprint)", job)
        return self.result(job)

    async def speak(self, request):
        self.authorize(request)
        try:
            data = await request.json()
        except Exception:
            raise HTTPException(400, "JSON permintaan tidak sah.")
        if not isinstance(data, dict) or data.get("preset") not in self.PRESETS:
            raise HTTPException(400, "Preset tidak sah.")
        preset = data["preset"]
        if not self.valid_wav(self.audio_dir / (preset + ".wav")):
            raise HTTPException(404, "Audio preset belum tersedia.")
        return self.enqueue(self.PRESETS[preset], self.base_url + "/static/megafon/" + preset + ".wav",
                            self.request_key(request), "preset:" + preset)

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
        ack = data.get("megaphone_ack")
        if isinstance(ack, str) and len(ack) <= 100:
            with self.db:
                # Its ACK means accepted by the local queue, not audible playback.
                self.db.execute("UPDATE jobs SET status='received', accepted_at=? WHERE id=? AND status='claimed'",
                                (self.now(), ack))
        if not self.switch()["enabled"]:
            return None
        job = self.db.execute("SELECT * FROM jobs WHERE status='claimed' ORDER BY created_at LIMIT 1").fetchone()
        if job is None:
            job = self.db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created_at LIMIT 1").fetchone()
        if job is None:
            return None
        with self.db:
            self.db.execute("UPDATE jobs SET status='claimed' WHERE id=?", (job["id"],))
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
        return {"ok": True, "version": "2.0", "aco_enabled": False,
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
                    "SELECT id,text,status,created_at,accepted_at,expires_at FROM jobs ORDER BY created_at DESC LIMIT 10")]}

    def pending(self):
        self.expire()
        row = self.db.execute("SELECT id,text,audio_url,created_at FROM jobs WHERE status IN ('queued','claimed') ORDER BY created_at LIMIT 1").fetchone()
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
                process = await asyncio.create_subprocess_exec(
                    "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", str(raw), "-t", "20", "-ar", "48000", "-ac", "1",
                    "-c:a", "pcm_s16le", "-f", "wav", str(temporary),
                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
                await asyncio.wait_for(process.wait(), timeout=12)
                if process.returncode != 0 or not self.valid_wav(temporary):
                    raise HTTPException(422, "Format rakaman tidak dapat ditukar kepada WAV.")
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


def install_remote_api(application, authorize, energy_provider, audio_dir, base_url, database):
    controller = RemoteController(audio_dir, base_url, database, authorize, energy_provider)
    controller.register(application)
    return controller

REMOTE = install_remote_api(app, check_key, lambda: car_energy_state, MEGAFON_DIR, BASE_URL, "/app/remote-state.sqlite3")

# AVAS commands use a dedicated persistent mailbox; legacy remote handlers stay separate.
try:
    from .avas_remote import install_avas_remote
except ImportError:
    from avas_remote import install_avas_remote

AVAS_REMOTE = install_avas_remote(
    app, check_key, os.environ.get("REMOTE_AVAS_AUDIO_DIR", "/app/avas-audio"),
    os.environ.get("REMOTE_AVAS_DATABASE", "/app/avas-state.sqlite3"))
