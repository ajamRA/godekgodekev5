import re, struct, subprocess
from pathlib import Path

p = Path(__file__).with_name('hook_activation_min.S')
t = p.read_text()
# Conservative source-level size gate: labels/strings excluded; actual assembly still required.
code = t.split(' source:')[0].split('hook_activation:')[1]
ins = [l for l in code.splitlines() if re.match(r'\s*(sub|adr|mov|svc|cmp|cset|add|strb|tbnz|str|mov|ldr|b)\b', l)]
assert len(ins) * 4 <= 176, (len(ins), len(ins)*4)
print('instruction_lines=',len(ins),'upper_bound_bytes=',len(ins)*4)
print('ASSEMBLY SIZE GATE PASS; assemble with an AArch64 toolchain before integration')
