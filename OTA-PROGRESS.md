# EX2-HU Wi-Fi ADB — progress and reproducibility

## Scope and status

Geely EX2 EX2-HU, Android 10, A/B OTA. Goal: persistent Wi-Fi ADB on TCP 5555, without changing system/vendor partitions or USB host mode.

**Experimental candidate built; vehicle test pending. Not a proven installation guide.** Local package validation is not vehicle acceptance, successful mounting, or working ADB. No GitHub upload/commit/push has been performed as part of this documentation update.

## Step-by-step work completed

1. Examined UpgradeService and vehicle logs. USB Update can reach `update_engine.applyPayload`; historical logs show completion code 0. The earlier claim that USB only updates MCU/GPS was incorrect.
2. Matched historical payloads to older boot images. Their post-boot logs still showed `ro.debuggable=0` and `init.svc.adbd=stopped`. Successful OTA write did not imply successful injection.
3. Parsed the candidate manifest: only `boot` is written. The other slot's existing system partition remains in use when that slot boots.
4. Examined normal-boot versus recovery ramdisk handling. The source-file lifetime across root switches and cleanup remains an unresolved runtime question. Absence of a `FreeRamdisk` string in a stripped binary does not prove absence of that logic.
5. Corrected CPIO placement to `first_stage_ramdisk/p` and `first_stage_ramdisk/w`. Preserved `default.prop` as a symlink to `prop.default`; only regular property files are edited.
6. Rejected the earlier proposed code cave at `0x22CDE`: it is not in the executable load segment. Zero bytes alone do not establish usable executable padding.
7. Encoded and disassembled the hook at `0xA9F50`: 80 bytes of instructions plus 72 bytes of aligned strings, total 152 bytes within the 176-byte budget. Entry branch: `0x6B6C8`; return: `0x6B6D8`, preserving original argv construction. Explicit stack/register checks cover x19/x29/x30.
8. Hook attempts two direct ARM64 mount syscalls (x8=40, MS_BIND): `/p` to `/system/etc/prop.default`, then `/w` to `/system/etc/init/blank_screen.rc`. This assumes `/p` and `/w` are still available at that execution point; not proven on the vehicle.
9. Integrated the checked encoder into `patch_boot_with_hook.py`. Added an explicit experimental build path; `SAR_HANDOFF_VERIFIED` remains False. Existing ancillary init/property patches remain and are not independently proven necessary or effective.
10. Added isolated OTA packaging through `build_chunked_ota.build_sar_candidate()`. It does not invoke the old main function's USB-copy path. Existing candidate ZIPs are refused rather than overwritten.
11. Built and validated the isolated candidate. Local checks passed for CPIO entries, symlink, hook bytes, boot size, DTBO preservation, operation hashes, reconstructed boot equality, local metadata/payload/ZIP signatures, and unchanged metadata/GPS/MCU extras.
12. Verified hashes of protected `output/update.zip`, `patched_build/boot.img`, and `original_backup/boot.img` remained unchanged during the build. This does not mean no source files changed: builder scripts were intentionally edited.

## Candidate identity (recorded build output)

Workspace: `D:/apps/ex2-ota/workspace_injection`

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `output/update_sar_candidate.zip` | 16774791 | `ff8e47c1b6b54f5c7ba6dd09693952eb191833d7d50ba246d2b96d566491b48e` |
| `patched_build/boot_sar_candidate.img` | 33554432 | `fc4a022bbc13c67e5e7e01cc9c458831f606f45c52c1de8e8a28f6949171faf5` |

These are recorded build results, not a fresh disk hash or confirmation of the file on a USB drive.

## Relevant scripts

All paths below are relative to `workspace_injection/`.

- `patch_boot_with_hook.py`: CPIO/boot patcher, experimental boot output.
- `model_hook_176.py`: encoder and Capstone static checks; no image writes.
- `test_sar_entries.py`: CPIO helpers and default build guard tests.
- `build_chunked_ota.py`: payload construction/signing and isolated candidate function. **Do not run its legacy main: it targets output/update.zip and may copy to USB.**
- `verify_sar_build.py`: experimental build plus validation. Refuses an existing candidate boot; not an idempotent read-only verification command.
- `ota_signer.py`: local whole-file OTA signing/verification support.

Read-only model/helper tests, from this workspace with the existing Windows virtual environment:

```bash
.venv/Scripts/python.exe model_hook_176.py
.venv/Scripts/python.exe test_sar_entries.py
```

The build script has already run successfully. Do not delete or overwrite candidates merely to rerun it. Existing absolute paths and local dependencies mean this is not yet a portable GitHub build workflow.

## Field test — pending, owner-operated

Firmware changes can cause boot failure. A/B does not guarantee recovery. Do not test while driving; establish stable power and a verified recovery procedure before deciding to flash. Preserve known-good files.

1. Confirm the exact candidate ZIP hash before copying. User handles the USB; no automatic copy or flash.
2. If proceeding, copy the candidate as `update.zip` at the FAT32 USB root and hash the copied file again.
3. Record the current slot/build. USB Update uses the inactive A/B slot; do not assume it is always A.
4. After the owner's USB Update and reboot, record boot outcome and active slot/build.
5. On a trusted private network only, test `adb connect <IP>:5555`, then `adb devices` and a shell command if connected. The candidate requests unauthenticated ADB; never expose it to public/shared networks.
6. Collect a fresh post-test log if unavailable. Record `ro.boot.slot_suffix`, `ro.debuggable`, `ro.secure`, `ro.adb.secure`, `init.svc.adbd`, and TCP port properties where present.
7. Do not infer mount success merely from `Parsing file /system/etc/init/blank_screen.rc`. This hook has **no klog marker and no mount return-value handling**. Ordinary logs may not prove whether either bind mount succeeded.

## Remaining work

- [ ] Vehicle runtime result and new log evidence.
- [ ] Confirm source availability and actual bind mounts at the execution point.
- [ ] Confirm ADB connection, authorization behavior, shell access and persistence after another reboot.
- [ ] Confirm ADB button visibility separately; more than one app hide condition exists.
- [ ] Review residual init patches and diagnostic coverage before calling the method reliable.
- [ ] Reconcile older handoff documents; their conflicting certainty about AVB, root switches and final-build success is not authoritative.

## GitHub preparation — not yet published

Publish an explicit allowlist of reviewed research notes, authored scripts and small tests. Do not use `git add .` on this workspace.

Exclude firmware images, OTA ZIPs, APKs, decompiled proprietary code, extracted partitions, vehicle logs, .env files, signing/private keys, credentials, VIN/account/device identifiers, virtual environments, caches and backups. Review scripts for hard-coded local paths and any credential-dependent setup. Confirm redistribution rights for any included third-party material.

Before publication: select files, redact sensitive data, add appropriate ignore rules, review the staged diff and secret scan, document dependencies/license, then obtain explicit approval to commit/push. Do not present the experimental candidate as a verified unlock or safe universal flashing method.
