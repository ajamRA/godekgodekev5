"""Run with the workspace .venv Python; no image writes."""
import copy
import patch_boot_with_hook as patcher

header = bytearray(b"070701" + b"00000000" * 13)
header[14:22] = b"0000a1ff"
entry = {"name": "default.prop", "hdr": header, "data": b"prop.default"}
before = copy.deepcopy(entry)
assert patcher.patch_property_entry(entry) is False
assert entry == before
for name in ("p", "w"):
    item = patcher.make_support_entry(header, name, b"example\n")
    assert item["name"] == "first_stage_ramdisk/" + name
    assert int(item["hdr"][14:22], 16) == 0o100644
    assert int(item["hdr"][94:102], 16) == len(item["name"].encode()) + 1
    assert int(item["hdr"][54:62], 16) == len(item["data"])
regular = patcher.make_support_entry(header, "p", b"ro.debuggable=0\n")
regular["name"] = "prop.default"
assert patcher.patch_property_entry(regular)
assert b"ro.debuggable=1\n" in regular["data"]
assert not patcher.SAR_HANDOFF_VERIFIED
try:
    patcher.main()
except SystemExit as error:
    assert str(error).startswith("BUILD BLOCKED:")
else:
    raise AssertionError("Incomplete builder must not write images")
print("PASS: symlink preserved; CPIO paths/modes/sizes; property patch; incomplete-build guard")
