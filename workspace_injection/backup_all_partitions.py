import os, subprocess, time, sys, hashlib

BACKUP_DIR = r"D:\apps\emas-ota\full_backup_car"
os.makedirs(BACKUP_DIR, exist_ok=True)
ADB_TARGET = "192.168.1.11:5555"

# Critical & standard partitions to backup
CRITICAL_PARTS = [
    # Bootloader & preloader
    ("mmcblk0boot0", "/dev/block/mmcblk0boot0"),
    ("mmcblk0boot1", "/dev/block/mmcblk0boot1"),
    ("boot_para", "/dev/block/by-name/boot_para"),
    ("para", "/dev/block/by-name/para"),
    ("ecarxpara", "/dev/block/by-name/ecarxpara"),
    ("ecarxparabak", "/dev/block/by-name/ecarxparabak"),
    ("lk_a", "/dev/block/by-name/lk_a"),
    ("lk_b", "/dev/block/by-name/lk_b"),
    
    # Unique Car Identity & Calibration (VERY IMPORTANT)
    ("proinfo", "/dev/block/by-name/proinfo"),
    ("nvram", "/dev/block/by-name/nvram"),
    ("nvdata", "/dev/block/by-name/nvdata"),
    ("nvcfg", "/dev/block/by-name/nvcfg"),
    ("protect1", "/dev/block/by-name/protect1"),
    ("protect2", "/dev/block/by-name/protect2"),
    ("persist", "/dev/block/by-name/persist"),
    ("seccfg", "/dev/block/by-name/seccfg"),
    ("sec1", "/dev/block/by-name/sec1"),
    ("otp", "/dev/block/by-name/otp"),
    ("flashinfo", "/dev/block/by-name/flashinfo"),
    ("persistent", "/dev/block/by-name/persistent"),
    ("mtkdata", "/dev/block/by-name/mtkdata"),
    ("resources", "/dev/block/by-name/resources"),
    ("metadata", "/dev/block/by-name/metadata"),
    ("frp", "/dev/block/by-name/frp"),
    ("expdb", "/dev/block/by-name/expdb"),
    ("md_udc", "/dev/block/by-name/md_udc"),
    
    # Kernel, Device Tree & Logos
    ("boot_a", "/dev/block/by-name/boot_a"),
    ("boot_b", "/dev/block/by-name/boot_b"),
    ("dtbo_a", "/dev/block/by-name/dtbo_a"),
    ("dtbo_b", "/dev/block/by-name/dtbo_b"),
    ("logo", "/dev/block/by-name/logo"),
    
    # AVB & Verified Boot
    ("vbmeta_a", "/dev/block/by-name/vbmeta_a"),
    ("vbmeta_b", "/dev/block/by-name/vbmeta_b"),
    ("vbmeta_system_a", "/dev/block/by-name/vbmeta_system_a"),
    ("vbmeta_system_b", "/dev/block/by-name/vbmeta_system_b"),
    ("vbmeta_vendor_a", "/dev/block/by-name/vbmeta_vendor_a"),
    ("vbmeta_vendor_b", "/dev/block/by-name/vbmeta_vendor_b"),
    
    # Subsystem Firmwares
    ("tee_a", "/dev/block/by-name/tee_a"),
    ("tee_b", "/dev/block/by-name/tee_b"),
    ("md1img_a", "/dev/block/by-name/md1img_a"),
    ("md1img_b", "/dev/block/by-name/md1img_b"),
    ("spmfw_a", "/dev/block/by-name/spmfw_a"),
    ("spmfw_b", "/dev/block/by-name/spmfw_b"),
    ("scp_a", "/dev/block/by-name/scp_a"),
    ("scp_b", "/dev/block/by-name/scp_b"),
    ("sspm_a", "/dev/block/by-name/sspm_a"),
    ("sspm_b", "/dev/block/by-name/sspm_b"),
    ("gz_a", "/dev/block/by-name/gz_a"),
    ("gz_b", "/dev/block/by-name/gz_b"),
    ("cam_vpu1_a", "/dev/block/by-name/cam_vpu1_a"),
    ("cam_vpu1_b", "/dev/block/by-name/cam_vpu1_b"),
    ("cam_vpu2_a", "/dev/block/by-name/cam_vpu2_a"),
    ("cam_vpu2_b", "/dev/block/by-name/cam_vpu2_b"),
    ("cam_vpu3_a", "/dev/block/by-name/cam_vpu3_a"),
    ("cam_vpu3_b", "/dev/block/by-name/cam_vpu3_b"),
]

def backup_partitions():
    print(f"Starting partition backup to {BACKUP_DIR}...")
    print(f"Total partitions in list: {len(CRITICAL_PARTS)}")
    
    success_count = 0
    total_bytes = 0
    t_start = time.time()
    
    for idx, (name, dev_path) in enumerate(CRITICAL_PARTS, 1):
        out_file = os.path.join(BACKUP_DIR, f"{name}.img")
        existing_sz = os.path.getsize(out_file) if os.path.exists(out_file) else 0
        if existing_sz > 0:
            print(f"[{idx}/{len(CRITICAL_PARTS)}] {name}: Already backed up ({existing_sz} bytes, {existing_sz/(1024*1024):.2f} MB) - SKIPPING")
            success_count += 1
            total_bytes += existing_sz
            continue
            
        print(f"[{idx}/{len(CRITICAL_PARTS)}] Dumping {name} from {dev_path}...", end=" ", flush=True)
        
        cmd = ["adb", "-s", ADB_TARGET, "exec-out", "dd", f"if={dev_path}", "bs=1M"]
        t0 = time.time()
        
        with open(out_file, "wb") as f_out:
            p = subprocess.Popen(cmd, stdout=f_out, stderr=subprocess.DEVNULL)
            p.wait()
            
        sz = os.path.getsize(out_file) if os.path.exists(out_file) else 0
        dur = time.time() - t0
        
        if sz > 0:
            print(f"OK ({sz} bytes, {sz/(1024*1024):.2f} MB in {dur:.2f}s)")
            success_count += 1
            total_bytes += sz
        else:
            print(f"FAILED (0 bytes)")
            
    t_total = time.time() - t_start
    print("=" * 60)
    print(f"Backup completed in {t_total:.2f}s!")
    print(f"Successfully backed up {success_count}/{len(CRITICAL_PARTS)} partitions.")
    print(f"Total backup size: {total_bytes} bytes ({total_bytes/(1024*1024):.2f} MB)")
    print(f"Saved directly to project folder: {BACKUP_DIR}")
    print("=" * 60)

if __name__ == "__main__":
    backup_partitions()
