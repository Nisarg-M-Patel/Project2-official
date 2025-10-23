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
    li $t9, 1
    sub $t0, $t0, $t9
    li $t1, 0
main_loop0:
    bgt $t0, $t1, main_exit0
    li $v0, 5
    syscall
    move $t1, $v0
    move $t9, $t0
    lw $t0, -4($fp)
    sll $t9, $t9, 2
    add $t0, $t9, $t0
    sw $t1, 0($t0)
    li $t9, 1
    add $t0, $t0, $t9
    j main_loop0
main_exit0:
    move $a0, $t1
    li $a1, 0
    move $a2, $t2
    jal quicksort
    li $t0, 0
main_loop1:
    bgt $t0, $t1, main_exit1
    move $t9, $t0
    sll $t9, $t9, 2
    lw $t8, -4($fp)
    add $t9, $t9, $t8
    lw $t1, 0($t9)
    move $a0, $t1
    li $v0, 1
    syscall
    li $a0, 10
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
    li $t0, 0
    li $t1, 0
    bge $t3, $t2, quicksort_end
    add $t0, $t2, $t1
    li $t9, 2
    div $t0, $t0, $t9
    move $t9, $t0
    sll $t9, $t9, 2
    lw $t8, -4($fp)
    add $t9, $t9, $t8
    lw $t3, 0($t9)
    li $t9, 1
    sub $t4, $t2, $t9
    li $t9, 1
    add $t5, $t1, $t9
quicksort_loop0:
quicksort_loop1:
    li $t9, 1
    add $t0, $t0, $t9
    move $t9, $t0
    sll $t9, $t9, 2
    lw $t8, -4($fp)
    add $t9, $t9, $t8
    lw $t2, 0($t9)
    move $t1, $t2
    blt $t1, $t4, quicksort_loop1
quicksort_loop2:
    li $t9, 1
    sub $t0, $t0, $t9
    move $t9, $t0
    sll $t9, $t9, 2
    lw $t8, -4($fp)
    add $t9, $t9, $t8
    lw $t2, 0($t9)
    move $t1, $t2
    bgt $t1, $t4, quicksort_loop2
    bge $t0, $t1, quicksort_exit0
    move $t9, $t4
    lw $t0, -4($fp)
    sll $t9, $t9, 2
    add $t0, $t9, $t0
    sw $t1, 0($t0)
    move $t9, $t3
    lw $t0, -4($fp)
    sll $t9, $t9, 2
    add $t0, $t9, $t0
    sw $t2, 0($t0)
    j quicksort_loop0
quicksort_exit0:
    li $t9, 1
    add $t2, $t0, $t9
    move $a0, $t1
    move $a1, $t4
    move $a2, $t0
    jal quicksort
    li $t9, 1
    add $t0, $t0, $t9
    move $a0, $t1
    move $a1, $t0
    move $a2, $t3
    jal quicksort
quicksort_end:
    lw   $ra, 0($sp)
    addi $sp, $sp, 4
    addi $sp, $sp, 44
    lw   $fp, 0($sp)
    addi $sp, $sp, 4
    jr $ra

