# AVAS TUNING FORMAT — TECHNICAL NOTES (libYin1.8_cs.so reverse-engineering)
# Diverified melalui disassembly Capstone AArch64 + empirical testing.
# Untuk AI/engineer yang nak edit tuning.txt / Config_Android.txt (bunyi AVAS IHU801P).

## SUMBER
- Binary: /vendor/lib64/libYin1.8_cs.so (83,248B, AArch64)
  Salinan PC: D:\apps\emas-ota\full_backup_car\libYin1.8_cs.so
- Fungsi utama: YIN_CFG_Read_Turning_Data (sekitar 0xd464-0xd640)
- Fungsi Verification: string "Fail to do verification!!!" / "Succeed in doing verification !!!" dalam binary
- Tuning files: /vendor/etc/avas/Config_Android.txt + /vendor/etc/avas/sound_type_{1,2,3}/tuning.txt & tuning_r.txt

## 1. ENCODING — XOR 0x70 DENGAN 4 EXCEPTIONS (bukan 3!)

Disassembly 0xd564-0xd5b4:
```
w8 = data[i]
if w8 <= 0x6f:  goto check_terminators   (0xd56c)
if w8 == 0x70:  SKIP (no XOR)            (0xd59c-0xd5a0)
if w8 == 0x7d:  SKIP (no XOR)            (0xd5a4-0xd5a8)   ← '}' — SELALU DILEPAS OLEH AI LAIN
if w8 == 0x0d:  SKIP (no XOR)            (0xd56c via cmp at 0xd570)
if w8 == 0x0a:  SKIP (no XOR)            (0xd56c via cmp at 0xd56c)
else:           w8 ^= 0x70; store
```

EXCEPTIONS LENGKAP: **0x0d (\r), 0x0a (\n), 0x70, 0x7d ('}')**
- AI sebelum ini sebut 3 je (\r, \n, 0x70) — TAK LENGKAP. '}' ialah penutup blok [stop]} dan TIDAK di-XOR.

Python decoder:
```python
EXC = {0x0d, 0x0a, 0x70, 0x7d}
decoded = bytes(b if b in EXC else (b ^ 0x70) for b in raw)
```
Encoder: operasi yang sama (XOR self-inverse), tapi guna exceptions yang sama semasa encode.

Contoh: 'Encryption' encoded = byte 0x45^0x70=0x35, dst; 'p' (0x70) KEKAL 0x70 → dalam encoded file nampak 'p' tetap 'p'... 
(sebenarnya 0x70 XOR 0x70 = 0x00 — tapi decoder skip 0x70 JUGA, jadi fail asli simpan 0x70? TIDAK —
decoder: if byte==0x70 skip. Encoder asal: byte 'p'=0x70 → if c==0x70: write as-is? Perlu test: 
dalam Config_Android.txt asal, 'Encryption' muncul sebagai "Encry\x00tion" apabila di-decode dgn XOR penuh.
Ini bermakna ENCODER asal XOR-kan SEMUA kecuali exceptions — 'p'(0x70) jadi 0x00 dalam fail encoded.
TAPI decoder skip 0x70 — jadi 0x00 kekal 0x00 selepas decode → huruf 'p' HILANG dalam decoded text!
REALITI dalam decoded output kami: "Encry\x00tion_Code" — memang ada \x00 pada tempat 'p'.
KESIMPULAN: ini BUG/FEATURE encoder Molead — huruf kecil 'p' (0x70) sentiasa hilang (jadi 0x00) dalam decoded view.
Decoder library: bila jumpa 0x70 encoded → skip (kekalkan 0x70). Bila jumpa 0x00 → kekal.
Maksudnya fail encoded menyimpan 'p' sebagai 0x00, decoder biarkan 0x00, dan library yin parse
dengan '\0' sebagai 'p' secara implisit? TIDAK — kemungkinan library yin sendiri yang encode/decode
secara simetri: raw 0x70 → store 0x70 (skip), dan raw 0x00 → store 0x00.
Dalam fail asli kami: byte pada posisi 'p' ialah 0x00. Selepas decoder kami (XOR kecuali exceptions):
0x00 ^ 0x70 = 0x70 = 'p'. TAPI decoder library skip exception... 0x00 bukan exception, jadi library
juga kira 0x00^0x70=0x70='p'. KONSISTEN! Fail encoded ada 0x00, decoded dapat 'p'.)

RUMUSAN MUKTAMAD: encode(decode(x)) == x. XOR pada SEMUA byte KECUALI {0x0d, 0x0a, 0x70, 0x7d}.
Byte 0x70 ('p') dalam plaintext akan menjadi 0x00 dalam encoded (0x70^0x70), dan BUKAN exception path.
Exception hanya mempengaruhi byte yang SUDAH berada dalam encoded file: nilai {0x0d,0x0a,0x70,0x7d}
kekal tidak disentuh semasa decode.

## 2. CRC16 — ALGORITHM & LOKASI

- Table CRC16-CCITT (poly 0x1021) berada di file offset **0x6b50** dalam libYin1.8_cs.so (256 entries, u16 LE).
  Verify: table[1] = 0x1021, standard test CRC("123456789", init=0xFFFF) = 0x29B1 ✓
- Init: 0xFFFF
- Skip: byte 0x0d (\r) SAHAJA semasa CRC (bukan \n!)
- Loop (0xd5e8-0xd61c):
```
crc = 0xFFFF
for b in data:
    if b == 0x0d: continue
    crc = ((crc << 8) & 0xFF00) ^ table[(b ^ (crc >> 8)) & 0xFF]
```

## 3. APA YANG DI-CRC — PENTING! (bonus discovery)

Empirical match dengan fail sebenar (Config_Android.txt, Encryption_Code:19575):

CRC = 19575 (0x4c77) dikira atas:
- **DECODED content** (bukan raw encrypted)
- **MINUS baris "Encryption_Code:NNNN\r\n" itu sendiri** (checksum tak boleh include dirinya)
- skip \r semasa iterate

Python lengkap (verify config):
```python
import struct, re
lib = open("libYin1.8_cs.so","rb").read()
table = struct.unpack_from('<256H', lib, 0x6b50)

def crc16(data):
    crc = 0xFFFF
    for b in data:
        if b == 0x0d: continue
        crc = ((crc << 8) & 0xFF00) ^ table[(b ^ (crc >> 8)) & 0xFF]
    return crc

EXC = {0x0d, 0x0a, 0x70, 0x7d}
def xor70(b): return b if b in EXC else b ^ 0x70

raw = open("Config_Android.txt","rb").read()
decoded = bytes(xor70(b) for b in raw)

# buang baris Encryption_Code
m = re.search(rb'Encryption_Code:\d+\r\n', decoded)
body = decoded[:m.start()] + decoded[m.end():]

code = crc16(body)               # = 19575 untuk config asal ✓
print(f"Encryption_Code sepatutnya: {code}")
```

## 4. CARA MENULIS FAIL BARU (re-encode)

```python
def encode_config(decoded_body: bytes, existing_raw: bytes) -> bytes:
    # 1. kira CRC atas decoded body (yang TANPA baris Encryption_Code)
    code = crc16(decoded_body)
    header = f"Encryption_Code:{code}\r\n".encode()
    full_decoded = header + decoded_body
    # 2. XOR-encode: XOR semua KECUALI exceptions (guna nilai byte ENCODED semasa semak exception)
    out = bytearray()
    for b in full_decoded:
        if b in EXC:
            out.append(b)
        else:
            out.append(b ^ 0x70)
    return bytes(out)
```
NOTA PENTING semasa encode: exception check dibuat atas nilai byte yang AKAN ditulis (encoded value),
untuk symmetry dengan decoder: decoder skip jika byte-encoded ∈ {0d,0a,70,7d}. Jika plaintext byte
adalah 0x7d ('}'), plaintext 0x7d ^ 0x70 = 0x0d yang merupakan exception-encoded → decoder akan
skip dan FAIL. Oleh itu plaintext TIDAK BOLEH mengandungi byte yang XOR-nya jatuh dalam exception set:
   plaintext c diharamkan jika (c ^ 0x70) ∈ {0x0d, 0x0a, 0x70, 0x7d}
   → c ∈ {0x7d, 0x7a, 0x00, 0x0d}... dalam praktik: JANGAN guna karakter '}' (0x7d) DAN '\0' dan
     karakter lain dalam set ni dalam plaintext yang nak di-encode (kecuali ia structural char yang
     library memang expects). Dalam config AVAS asal, hanya '\r','\n','}','p' yang terjejas —
     dan fail asal Molead memang mengandungi semua ini tanpa masalah, kerana MEREKA yang generate
     dengan encoder mereka sendiri. Jika anda create config dari scratch, test dengan Verification
     path library (atau verify dengan dump log AVAS service masa boot).

## 5. STRUKTUR CONFIG (dah decoded, rujukan)

Config_Android.txt decoded:
```
Encryption_Code:19575
Module:MODULE_AVAS
Car_Oem:Proton
Car_Tier1:Molead
Car_Type:0x00
Device_Node:hw:0,29
Audio_Format:1
Channel_Num:2
Sample_Rate:48000
Bit_Sample:32
Nolock_Flag:1
Buffer_Len:2048
Sound_Mode:1
Path:/vendor/etc/avas/sound_type_1/tuning.txt
Sound_Mode:2
Path:/vendor/etc/avas/sound_type_2/tuning.txt
Sound_Mode:3
Path:/vendor/etc/avas/sound_type_3/tuning.txt
Sound_Mode:4
Path:/vendor/etc/avas/sound_type_1/tuning_r.txt
Sound_Mode:5
Path:/vendor/etc/avas/sound_type_2/tuning_r.txt
Sound_Mode:6
Path:/vendor/etc/avas/sound_type_3/tuning_r.txt
[stop]}
```
(tuning_r = reverse gear sound config)

tuning.txt (mode 3) decoded (ringkas):
```
Encryption_Code:383388
Sound_Mode:3
Pedal:0
Break:0
Volume_type:31,31,31
Track:/vendor/etc/avas/sound_type_3/bDM_01_01.wav
Mixer_Volume:10,10,10
Pitch:100,108,116,124,132,140,148        ← pitch ikut speed (7 speed bands)
Volume:0,2,5,6,7,2,0                      ← volume ikut speed
Track:/vendor/etc/avas/sound_type_3/pixel.wav
Mixer_Volume:10,10,10
Pitch:100,100,100,100,100,100,100
Volume:0,3,5,6,9,4,0
[stop]}
```

## 6. STATE DEVICE (semasa notes ini ditulis)

- AVAS persistence AKTIF melalui /system/etc/init/zzz_avas_boot.rc + /system/bin/avas_boot.sh
  (dibuat oleh "AI #2") — run setiap boot, bind /data/local/avas/pixel_factory.wav (581,788B) 
  ke /vendor/etc/avas/sound_type_3/pixel.wav, restorecon, set props, kill service.
  Boot log: /data/local/avas/avas_boot.log. VERIFIED berfungsi selepas reboot penuh.
- Vendor 100% penuh — JANGAN tulis ke /vendor. Guna bind mount dari /data sahaja.
- Fail custom dalam /data/local/avas/: pixel_factory.wav (original), custom_v8.wav (2,287,152B),
  v8_clean.wav, silent.wav (JANGAN GUNA), thermal_manager.orig, start_resources.orig
- Untuk tukar bunyi custom (Faza 4): edit SRC= dalam /system/bin/avas_boot.sh ke fail lain
  (cth: /data/local/avas/custom_v8.wav), pastu reboot atau jalankan script manual.
- Tuning.txt juga boleh di-edit (guna format di atas) untuk adjust pitch/volume curve custom sound.

## 7. RINGKASAN VERIFIKASI AI#3

| Claim AI#3 | Status |
|---|---|
| XOR 0x70 encoding | ✅ Betul |
| Exceptions: \r, \n, 0x70 | ⚠️ TAK LENGKAP — ada ke-4: 0x7d ('}') |
| CRC16 table @ 0x6b50 | ✅ Betul — CCITT poly 0x1021 |
| Init 0xFFFF, skip \r | ✅ Betul |
| CRC atas "after Encryption_Code line" | ⚠️ Kurang tepat — sebenarnya: whole decoded MINUS Encryption_Code line |
