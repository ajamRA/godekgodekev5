"""Static-only model: bind patched second-stage init + prop.default.
No boot image or OTA mutation.
"""
import struct
import capstone

BASE = 0xA9F50
RETURN = 0x6B6D8
ENTRY = 0x6B6C8
BUDGET = 176
EXISTING_INIT = 0x1113E  # existing `/system/bin/init\0` string


def adr(reg, target, pc):
    delta = target - pc
    assert -(1 << 20) <= delta < (1 << 20)
    return 0x10000000 | ((delta & 3) << 29) | (((delta >> 2) & 0x7FFFF) << 5) | reg


def branch(target, pc):
    delta = target - pc
    assert delta % 4 == 0
    return 0x14000000 | ((delta >> 2) & 0x3FFFFFF)


def build():
    # Source literals: patched init path plus prop source/target.
    # Existing target `/system/bin/init` is referenced at 0x1113E.
    literals = [b"/first_stage_ramdisk/system/bin/init\0",
                b"/first_stage_ramdisk/p\0"]
    code_words = [
        0xA9BE7BFD,  # stp x29,x30,[sp,#-32]!
        0x910003FD,  # mov x29,sp
        0xF9000BF3,  # str x19,[sp,#16]
    ]
    # Data begins after the actual code block (80 bytes).
    data = bytearray(); addresses = {}
    for key, value in zip(("init_src", "prop"), literals):
        while len(data) % 4: data += b"\0"
        addresses[key] = BASE + 80 + len(data)
        data += value
    # init bind: x0=source, x1=existing target string, x2=NULL, x3=MS_BIND, x4=NULL, x8=40
    code_words += [adr(0, addresses["init_src"], BASE + len(code_words)*4),
                   adr(1, EXISTING_INIT, BASE + (len(code_words)+1)*4),
                   0xAA1F03E2, 0x52820003, 0xAA1F03E4,
                   0xD2800508, 0xD4000001]
    # Mount the injected property file, never self-bind the stock target.
    while len(data) % 4: data += b"\0"
    addresses["prop_target"] = BASE + 80 + len(data)
    data += b"/system/etc/prop.default\0"
    code_words += [adr(0, addresses["prop"], BASE + len(code_words)*4),
                   adr(1, addresses["prop_target"], BASE + (len(code_words)+1)*4),
                   0xAA1F03E2, 0x52820003, 0xAA1F03E4,
                   0xD2800508, 0xD4000001]
    # Append epilog first: list RHS evaluation sees the OLD list length.
    code_words += [0xF9400BF3, 0xA8C27BFD]
    code_words.append(branch(RETURN, BASE + len(code_words)*4))
    code = b"".join(struct.pack("<I", word) for word in code_words)
    blob = code + data
    assert len(code_words) == 20
    assert len(code) == 80
    assert len(blob) <= BUDGET
    return blob, addresses


def check():
    blob, addresses = build()
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    md.detail = True
    ins = list(md.disasm(blob[:80], BASE))
    assert len(ins) == 20
    print('RETURN_DECODED:', hex(ins[-1].operands[0].imm))
    assert ins[-1].operands[0].imm == RETURN
    for index, target in [(3, addresses["init_src"]), (4, EXISTING_INIT),
                          (10, addresses["prop"]), (11, addresses["prop_target"])]:
        assert ins[index].mnemonic == 'adr'
        assert ins[index].operands[1].imm == target
    for key, expected in [('prop', b'/first_stage_ramdisk/p\0'),
                          ('prop_target', b'/system/etc/prop.default\0')]:
        offset = addresses[key] - BASE
        assert blob[offset:offset + len(expected)] == expected
    assert addresses['prop'] != addresses['prop_target']
    print('PASS: return, all four ADR targets, and distinct property source/target')
    assert all(address % 4 == 0 for address in addresses.values())
    entry = list(md.disasm(struct.pack('<I', 0x54000001 | (((BASE-ENTRY)//4 & 0x7FFFF)<<5)), ENTRY))[0]
    assert entry.operands[0].imm == BASE
    print('ENTRY:', entry.mnemonic, entry.op_str)
    for x in ins: print(f'{x.address:#x}: {x.bytes.hex()}  {x.mnemonic} {x.op_str}')
    print('LITERALS:', {k: hex(v) for k,v in addresses.items()})
    print(f'PASS STATIC: code={len(ins)*4}, total={len(blob)}, slack={BUDGET-len(blob)}')
    print('GUARD: SAR_HANDOFF_VERIFIED remains False; no image/ZIP generated')


if __name__ == '__main__':
    check()
