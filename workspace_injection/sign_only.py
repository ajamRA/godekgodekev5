import os, sys, struct, hashlib, zipfile, base64
from payload_dumper import update_metadata_pb2
from ota_signer import sign_whole_file_ota

ROOT_DIR = r"D:\apps\emas-ota\workspace_injection"
PARTS_DIR = r"D:\apps\emas-ota\patched_parts"
OUTPUT_DIR = r"D:\apps\emas-ota\workspace_injection\output"
OUT_ZIP = os.path.join(OUTPUT_DIR, "update_full.zip")
TOOLS_DIR = os.path.join(ROOT_DIR, "tools")
PRIV_KEY_PATH = os.path.join(TOOLS_DIR, "testkey.pk8")
BASE_SIG_PATH = os.path.join(ROOT_DIR, "clean_sig.der")

sign_whole_file_ota(OUT_ZIP + ".unsigned", OUT_ZIP, PRIV_KEY_PATH, BASE_SIG_PATH)
print("Done.")
