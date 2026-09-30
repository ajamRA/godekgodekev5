import re

with open('/home/ubuntu/emas5/server.py', 'r', encoding='utf-8') as f:
    c = f.read()

# 1. Update megafon_speak to auto-activate fast sync
speak_target = """    car_megaphone_pending = {
        "id": msg_id,
        "text": text or preset or "Voice Broadcast",
        "audio_url": url,
        "timestamp": int(time.time())
    }"""
speak_replace = """    car_megaphone_pending = {
        "id": msg_id,
        "text": text or preset or "Voice Broadcast",
        "audio_url": url,
        "timestamp": int(time.time())
    }
    car_megaphone_switch["enabled"] = True
    car_megaphone_switch["updated_at"] = int(time.time())
    car_megaphone_switch["expires_at"] = int(time.time()) + 120"""

if speak_target in c:
    c = c.replace(speak_target, speak_replace)
    print("Patched megafon_speak auto-switch")
else:
    print("Warning: speak_target not found")

# 2. Update megafon_upload to auto-activate fast sync
upload_target = """    car_megaphone_pending = {
        "id": msg_id,
        "text": "Voice Broadcast",
        "audio_url": f"{BASE_URL}/static/megafon/{out_file}",
        "timestamp": int(time.time())
    }"""
upload_replace = """    car_megaphone_pending = {
        "id": msg_id,
        "text": "Voice Broadcast",
        "audio_url": f"{BASE_URL}/static/megafon/{out_file}",
        "timestamp": int(time.time())
    }
    car_megaphone_switch["enabled"] = True
    car_megaphone_switch["updated_at"] = int(time.time())
    car_megaphone_switch["expires_at"] = int(time.time()) + 120"""

if upload_target in c:
    c = c.replace(upload_target, upload_replace)
    print("Patched megafon_upload auto-switch")
else:
    print("Warning: upload_target not found")

# 3. Update car_sync with ACK, expiration, and responsive polling interval
sync_old = """    global car_megaphone_pending
    mega_to_send = None
    if car_megaphone_pending:
        mega_to_send = car_megaphone_pending
        car_megaphone_pending = None
        print(f"[SYNC] Dispatched megafon message='{mega_to_send}' to car daemon")

    avas_st = load_avas_state()
    lock_st = load_lock_state()
    unlock_st = load_unlock_state()
    now_sync = int(time.time())
    if car_megaphone_switch["enabled"] and car_megaphone_switch["expires_at"] > 0 and now_sync > car_megaphone_switch["expires_at"]:
        car_megaphone_switch["enabled"] = False
    poll_interval = 1.5 if car_megaphone_switch["enabled"] else 45.0"""

sync_new = """    global car_megaphone_pending
    if "megaphone_ack" in data and car_megaphone_pending:
        if str(car_megaphone_pending.get("id")) == str(data["megaphone_ack"]):
            print(f"[SYNC] Car ACKed megaphone message '{data['megaphone_ack']}'. Cleared from pending.")
            car_megaphone_pending = None

    if car_megaphone_pending and (int(time.time()) - car_megaphone_pending.get("timestamp", 0) > 120):
        print(f"[SYNC] Megaphone message '{car_megaphone_pending.get('id')}' expired (>120s). Cleared.")
        car_megaphone_pending = None

    mega_to_send = None
    if car_megaphone_pending:
        mega_to_send = car_megaphone_pending

    avas_st = load_avas_state()
    lock_st = load_lock_state()
    unlock_st = load_unlock_state()
    now_sync = int(time.time())
    if car_megaphone_switch["enabled"] and car_megaphone_switch["expires_at"] > 0 and now_sync > car_megaphone_switch["expires_at"]:
        car_megaphone_switch["enabled"] = False
    poll_interval = 1.2 if car_megaphone_switch["enabled"] else 2.5"""

if sync_old in c:
    c = c.replace(sync_old, sync_new)
    print("Patched car_sync with ACK and responsive polling")
else:
    print("Warning: sync_old not found")

with open('/home/ubuntu/emas5/server.py', 'w', encoding='utf-8') as f:
    f.write(c)

print("SUCCESS: server.py updated.")
