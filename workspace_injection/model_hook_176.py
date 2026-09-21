"""Static ARM64 encoding model only. Never writes an image or payload.
/p and /w availability after system-root switch/FreeRamdisk is UNPROVEN.
Run with workspace .venv Python (capstone installed).
"""
import struct
import capstone

BASE = 0xA9F50
ENTRY = 0x6B6C8
RETURN = 0x6B6D8
BUDGET = 176


def adr(reg, target, pc):
    delta = target - pc
    assert 0 <= reg < 31 and -(1 << 20) <= delta < (1 << 20)
    return 0x10000000 | ((delta & 3) << 29) | (((delta >> 2) & 0x7ffff) << 5) | reg


def branch(target, pc, conditional=False):
    delta = target - pc
    assert delta % 4 == 0
    bits = 19 if conditional else 26
    assert -(1 << (bits - 1)) <= delta // 4 < (1 << (bits - 1))
    imm = (delta // 4) & ((1 << bits) - 1)
    return (0x54000001 | (imm << 5)) if conditional else (0x14000000 | imm)


def build():
    # Twenty instructions, including BOTH mov x8,#40; no unused x5 writes.
    code_size = 20 * 4
    data = bytearray()
    addresses = {}
    for key, text in [('p', b'/p\0'), ('prop', b'/system/etc/prop.default\0'),
                      ('w', b'/w\0'), ('rc', b'/system/etc/init/blank_screen.rc\0')]:
        while len(data) % 4:
            data.append(0)
        addresses[key] = BASE + code_size + len(data)
        data.extend(text)
    while len(data) % 4:
        data.append(0)
    words = [0xA9BE7BFD, 0x910003FD, 0xF9000BF3]
    for src, dst in [('p', 'prop'), ('w', 'rc')]:
        words.append(adr(0, addresses[src], BASE + len(words) * 4))
        words.append(adr(1, addresses[dst], BASE + len(words) * 4))
        words.extend([0xAA1F03E2, 0x52820003, 0xAA1F03E4,
                      0xD2800508, 0xD4000001])  # NULL, MS_BIND, NULL, x8=40, svc
    words.extend([0xF9400BF3, 0xA8C27BFD])
    words.append(branch(RETURN, BASE + len(words) * 4))
    code = b''.join(struct.pack('<I', word) for word in words)
    assert len(code) == code_size
    blob = code + data
    assert len(blob) <= BUDGET
    return blob, code_size, addresses


def check():
    blob, code_size, addresses = build()
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    md.detail = True
    instructions = list(md.disasm(blob[:code_size], BASE))
    assert len(instructions) == 20
    for ins in instructions:
        print(f'{ins.address:#x}: {ins.bytes.hex()}  {ins.mnemonic} {ins.op_str}')
    for idx, key in [(3, 'p'), (4, 'prop'), (10, 'w'), (11, 'rc')]:
        assert instructions[idx].mnemonic == 'adr'
        assert instructions[idx].operands[1].imm == addresses[key]
    for idx in (8, 15):
        assert instructions[idx].mnemonic == 'mov'
        assert instructions[idx].op_str == 'x8, #0x28'
        assert instructions[idx + 1].mnemonic == 'svc'
    assert instructions[-1].operands[0].imm == RETURN
    entry = list(md.disasm(struct.pack('<I', branch(BASE, ENTRY, True)), ENTRY))[0]
    assert entry.mnemonic == 'b.ne' and entry.operands[0].imm == BASE
    assert instructions[0].op_str == 'x29, x30, [sp, #-0x20]!'
    assert instructions[2].op_str == 'x19, [sp, #0x10]'
    assert instructions[-3].op_str == 'x19, [sp, #0x10]'
    assert instructions[-2].op_str == 'x29, x30, [sp], #0x20'
    # Symbolic stack round-trip for the explicitly preserved registers.
    sp, x19, x29, x30 = 0x1000, 19, 29, 30
    initial = (sp, x19, x29, x30)
    stack = {sp - 32: x29, sp - 24: x30, sp - 16: x19}
    sp -= 32
    x29 = sp
    x19 = stack[sp + 16]
    x29, x30 = stack[sp], stack[sp + 8]
    sp += 32
    assert (sp, x19, x29, x30) == initial
    assert all(addr % 4 == 0 for addr in addresses.values())
    print('ENTRY:', entry.mnemonic, entry.op_str)
    print('LITERALS:', {key: hex(value) for key, value in addresses.items()})
    print(f'PASS static: code={code_size}, aligned_data={len(blob)-code_size}, total={len(blob)}, slack={BUDGET-len(blob)}')
    print('NOT runtime-tested. No source preservation, mount error handling or klog. Guard remains closed.')


if __name__ == '__main__':
    check()
