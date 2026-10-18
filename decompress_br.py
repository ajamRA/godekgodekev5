import brotli
import sys
import time

br_path = r"D:\apps\emas-ota\Geely Xingyuan\extracted_test\system.new.dat.br"
out_path = r"D:\apps\emas-ota\Geely Xingyuan\extracted_test\system.new.dat"

print(f"Decompressing {br_path} -> {out_path} ...")
start = time.time()

decompressor = brotli.Decompressor()
with open(br_path, "rb") as fin, open(out_path, "wb") as fout:
    while True:
        chunk = fin.read(1024 * 1024 * 8)
        if not chunk:
            break
        decompressed = decompressor.process(chunk)
        if decompressed:
            fout.write(decompressed)

print(f"Decompression finished in {time.time() - start:.2f}s")
