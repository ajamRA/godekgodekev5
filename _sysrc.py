import struct, sys, re

PATH = sys.argv[1] if len(sys.argv) > 1 else "parts/system.img"
NEEDLES = [b"on property:ro.debuggable", b"ro.debuggable=1", b"start adbd",
           b"service.adb.tcp.port", b"export ANDROID_ROOT /system", b"init.svc.adbd"]

CHUNK = 8*1024*1024
OVER = 4096

# sparse?
with open(PATH, "rb") as f:
    head = f.read(4)
print("magic:", head.hex(), "(sparse)" if head == bytes.fromhex("3aff26ed") else "(raw)")
print("needles:", [n.decode() for n in NEEDLES])
print("="*90)

size = 0
found = {}
with open(PATH, "rb") as f:
    prev = b""
    while True:
        buf = f.read(CHUNK)
        if not buf:
            break
        data = prev + buf
        base = size - len(prev)
        for nd in NEEDLES:
            start = 0
            while True:
                i = data.find(nd, start)
                if i == -1:
                    break
                found.setdefault(nd, []).append(base + i)
                start = i + 1
        prev = data[-OVER:]
        size += len(buf)

print(f"image size = {size} bytes ({size/1e9:.2f} GB)")
for nd in NEEDLES:
    print(f"  {nd.decode():32s} -> {len(found.get(nd,[]))} hit(s)")

# dump context of 'on property:ro.debuggable'
offs = found.get(b"on property:ro.debuggable", [])
for off in offs[:3]:
    with open(PATH, "rb") as f:
        f.seek(max(0, off-1500)); blob = f.read(4000)
    txt = blob.decode("latin1", "replace")
    print("\n" + "="*90)
    print(f"CONTEXT @ 0x{off:x}")
    print("="*90)
    lines = [l for l in txt.splitlines()]
    for i, l in enumerate(lines):
        if "ro.debuggable" in l:
            for j in range(max(0,i-12), min(len(lines), i+14)):
                print(("  >> " if j==i else "     ") + l[:150])
            break
