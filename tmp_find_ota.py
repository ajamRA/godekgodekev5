import os
roots = [
    r"C:\Users\user\Downloads",
    r"C:\Users\user\Desktop",
    r"C:\Users\user\Documents",
    r"D:\apps",
]
needles = ["3C6025", "SW0E22", "user_995", "H0128", "H111100000"]
print("cwd scan start")
for root in roots:
    if not os.path.isdir(root):
        print("missing", root)
        continue
    print("SCAN", root)
    try:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in ("node_modules", ".git", "AppData")]
            depth = dirpath[len(root):].count(os.sep)
            if depth > 3:
                dirnames.clear()
                continue
            for fn in filenames:
                if any(n.lower() in fn.lower() for n in needles) or (
                    fn.lower().endswith(".zip") and "995" in fn
                ):
                    p = os.path.join(dirpath, fn)
                    try:
                        sz = os.path.getsize(p)
                    except OSError:
                        sz = -1
                    print(f"FOUND {sz/1024/1024:.1f}MB  {p}")
    except Exception as e:
        print("ERR", root, e)
print("DONE")
