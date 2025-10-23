.text
main:
    addi $sp, $sp, -4
    sw   $fp, 0($sp)
    move $fp, $sp
    addi $sp, $sp, -16
    addi $sp, $sp, -4
    sw   $ra, 0($sp)
    li $v0, 9
    li $a0, 400
    syscall
    sw $v0, -4($fp)
    li $t1, 0
    li $v0, 5
    syscall
    move $t0, $v0
    li $t9, 100
    bgt $t0, $t9, main_return
    lw $t0, -16($fp)
    li $t9, 1
    sub $t0, $t0, $t9
    li $t1, 0
    sw $t1, -12($fp)
    sw $t0, -16($fp)
main_loop0:
    lw $t0, -12($fp)
    lw $t1, -16($fp)
    bgt $t0, $t1, main_exit0
    li $v0, 5
    syscall
    move $t1, $v0
    lw $t0, -12($fp)
    lw $t7, -4($fp)
    sll $t6, $t0, 2
    add $t7, $t6, $t7
    sw $t1, 0($t7)
    li $t9, 1
    add $t0, $t0, $t9
    j main_loop0
main_exit0:
    lw $t0, -4($fp)
    move $a0, $t0
    li $t8, 0
    move $a1, $t8
    lw $t2, -16($fp)
    move $a2, $t2
    jal quicksort
    li $t1, 0
    sw $t1, -12($fp)
main_loop1:
    lw $t0, -12($fp)
    lw $t1, -16($fp)
    bgt $t0, $t1, main_exit1
    lw $t0, -12($fp)
    sll $t9, $t0, 2
    lw $t8, -4($fp)
    add $t9, $t9, $t8
    lw $t1, 0($t9)
    move $a0, $t1
    li $v0, 1
    syscall
    li $t8, 10
    move $a0, $t8
    li $v0, 11
    syscall
    li $t9, 1
    add $t0, $t0, $t9
    j main_loop1
main_exit1:
main_return:
    lw   $ra, 0($sp)
    addi $sp, $sp, 4
    addi $sp, $sp, 16
    lw   $fp, 0($sp)
    addi $sp, $sp, 4
    li $v0, 10
    syscall

quicksort:
    addi $sp, $sp, -4
    sw   $fp, 0($sp)
    move $fp, $sp
    sw $a0, -4($fp)
    sw $a1, -8($fp)
    sw $a2, -12($fp)
    addi $sp, $sp, -44
    addi $sp, $sp, -4
    sw   $ra, 0($sp)
    li $t2, 0
    li $t3, 0
    lw $t1, -8($fp)
    lw $t0, -12($fp)
    bge $t1, $t0, quicksort_end
    lw $t2, -8($fp)
    lw $t1, -12($fp)
    add $t0, $t2, $t1
    li $t9, 2
    div $t0, $t0, $t9
    sll $t9, $t0, 2
    lw $t8, -4($fp)
    add $t9, $t9, $t8
    lw $t4, 0($t9)
    li $t9, 1
    sub $t5, $t2, $t9
    li $t9, 1
    add $t6, $t1, $t9
    sw $t1, -12($fp)
    sw $t2, -8($fp)
    sw $t0, -32($fp)
    sw $t4, -36($fp)
    sw $t5, -40($fp)
    sw $t6, -44($fp)
quicksort_loop0:
quicksort_loop1:
    lw $t0, -40($fp)
    li $t9, 1
    add $t0, $t0, $t9
    sll $t9, $t0, 2
    lw $t8, -4($fp)
    add $t9, $t9, $t8
    lw $t2, 0($t9)
    move $t1, $t2
    lw $t4, -36($fp)
    blt $t1, $t4, quicksort_loop1
quicksort_loop2:
    lw $t0, -44($fp)
    li $t9, 1
    sub $t0, $t0, $t9
    sll $t9, $t0, 2
    lw $t8, -4($fp)
    add $t9, $t9, $t8
    lw $t2, 0($t9)
    move $t1, $t2
    lw $t4, -36($fp)
    bgt $t1, $t4, quicksort_loop2
    lw $t0, -40($fp)
    lw $t1, -44($fp)
    bge $t0, $t1, quicksort_exit0
    lw $t4, -44($fp)
    lw $t1, -16($fp)
    lw $t7, -4($fp)
    sll $t6, $t4, 2
    add $t7, $t6, $t7
    sw $t1, 0($t7)
    lw $t3, -40($fp)
    lw $t2, -20($fp)
    lw $t7, -4($fp)
    sll $t6, $t3, 2
    add $t7, $t6, $t7
    sw $t2, 0($t7)
    j quicksort_loop0
quicksort_exit0:
    lw $t0, -44($fp)
    li $t9, 1
    add $t4, $t0, $t9
    lw $t1, -4($fp)
    move $a0, $t1
    lw $t3, -8($fp)
    move $a1, $t3
    move $a2, $t0
    jal quicksort
    lw $t0, -44($fp)
    li $t9, 1
    add $t0, $t0, $t9
    lw $t1, -4($fp)
    move $a0, $t1
    move $a1, $t0
    lw $t2, -12($fp)
    move $a2, $t2
    jal quicksort
quicksort_end:
    lw   $ra, 0($sp)
    addi $sp, $sp, 4
    addi $sp, $sp, 44
    lw   $fp, 0($sp)
    addi $sp, $sp, 4
    jr $ra

