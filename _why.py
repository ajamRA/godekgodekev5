import os, glob, re

SESSION = "_logs/m1/APLog_2026_0906_154855__24"   # first boot on slot A AFTER our Sept-6 flash
SESSION2 = "latest_car_log/APLog_2026_0907_151750__3"  # first boot on slot A AFTER our Sept-7 flash

PATS = [
    r"IHU801P-user", r"Aug\s+7", r"userdebug", r"first_stage_ramdisk/p", r"adb_debug",
    r"MS_BIND", r"unbootable", r"rollback", r"SlotVerify", r"boot_a", r"prop\.default",
    r"Switching root", r"init\.rc:8[0-9][0-9]", r"AVB HASHTREE", r"VerificationDisabled",
    r"ro\.build\.flavor", r"ro\.build\.date=", r"Parsing file /system/etc/init/blank_screen",
    r"load_properties|LoadProperties|property file",
]

for S in (SESSION, SESSION2):
    print("=" * 110)
    print("SESSION:", S)
    if not os.path.isdir(S): print("  MISSING"); continue
    for f in sorted(glob.glob(os.path.join(S, "**", "*"), recursive=True)):
        if not os.path.isfile(f): continue
        try: txt = open(f, encoding="utf-8", errors="replace").read()
        except Exception: continue
        pat = re.compile("|".join(PATS))
        seen = set(); out = []
        for line in txt.splitlines():
            if not pat.search(line): continue
            k = re.sub(r"^[<\d\-\s:.\[\]]+", "", line)[:130]
            if k in seen: continue
            seen.add(k); out.append(line.strip()[:230])
        if not out: continue
        print(f"\n  --- {os.path.relpath(f, S)} ({len(out)} uniq)")
        for l in out[:22]:
            print("     ", l)
