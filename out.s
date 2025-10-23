.text
main:
    addi $sp, $sp, -4
    sw $fp, 0($sp)
    move $fp, $sp
    addi $sp, $sp, -416
    li $v0, 9
    li $a0, 400
    syscall
    sw $v0, -4($fp)
    li $t0, 0
    sw $t0, -404($fp)
    li $v0, 5
    syscall
    sw $v0, -412($fp)
    lw $t0, -412($fp)
    li $t1, 100
    bgt $t0, $t1, main_return
    lw $t0, -412($fp)
    li $t1, 1
    sub $t2, $t0, $t1
    sw $t2, -412($fp)
    li $t0, 0
    sw $t0, -408($fp)
main_loop0:
    lw $t0, -408($fp)
    lw $t1, -412($fp)
    bgt $t0, $t1, main_exit0
    li $v0, 5
    syscall
    sw $v0, -404($fp)
    lw $t2, -404($fp)
    lw $t0, -4($fp)
    lw $t1, -408($fp)
    sll $t1, $t1, 2
    add $t0, $t0, $t1
    sw $t2, 0($t0)
    lw $t0, -408($fp)
    li $t1, 1
    add $t2, $t0, $t1
    sw $t2, -408($fp)
    j main_loop0
main_exit0:
    addi $sp, $sp, -32
    sw $t0, 0($sp)
    sw $t1, 4($sp)
    sw $t2, 8($sp)
    sw $t3, 12($sp)
    sw $t4, 16($sp)
    sw $t5, 20($sp)
    sw $t6, 24($sp)
    sw $t7, 28($sp)
    lw $a0, -4($fp)
    li $a1, 0
    lw $a2, -412($fp)
    jal quicksort
    lw $t0, 0($sp)
    lw $t1, 4($sp)
    lw $t2, 8($sp)
    lw $t3, 12($sp)
    lw $t4, 16($sp)
    lw $t5, 20($sp)
    lw $t6, 24($sp)
    lw $t7, 28($sp)
    addi $sp, $sp, 32
    li $t0, 0
    sw $t0, -408($fp)
main_loop1:
    lw $t0, -408($fp)
    lw $t1, -412($fp)
    bgt $t0, $t1, main_exit1
    lw $t0, -4($fp)
    lw $t1, -408($fp)
    sll $t1, $t1, 2
    add $t0, $t0, $t1
    lw $t2, 0($t0)
    sw $t2, -404($fp)
    lw $a0, -404($fp)
    li $v0, 1
    syscall
    li $a0, 10
    li $v0, 11
    syscall
    lw $t0, -408($fp)
    li $t1, 1
    add $t2, $t0, $t1
    sw $t2, -408($fp)
    j main_loop1
main_exit1:
main_return:
    addi $sp, $sp, 416
    lw $fp, 0($sp)
    addi $sp, $sp, 4
    li $v0, 10
    syscall
quicksort:
    addi $sp, $sp, -4
    sw $fp, 0($sp)
    move $fp, $sp
    addi $sp, $sp, -48
    li $t0, 0
    sw $t0, -40($fp)
    li $t0, 0
    sw $t0, -44($fp)
    lw $t0, -8($fp)
    lw $t1, -12($fp)
    bge $t0, $t1, quicksort_end
    lw $t0, -8($fp)
    lw $t1, -12($fp)
    add $t2, $t0, $t1
    sw $t2, -32($fp)
    lw $t0, -32($fp)
    li $t1, 2
    div $t2, $t0, $t1
    sw $t2, -32($fp)
    lw $t0, -4($fp)
    lw $t1, -32($fp)
    sll $t1, $t1, 2
    add $t0, $t0, $t1
    lw $t2, 0($t0)
    sw $t2, -36($fp)
    lw $t0, -8($fp)
    li $t1, 1
    sub $t2, $t0, $t1
    sw $t2, -40($fp)
    lw $t0, -12($fp)
    li $t1, 1
    add $t2, $t0, $t1
    sw $t2, -44($fp)
quicksort_loop0:
quicksort_loop1:
    lw $t0, -40($fp)
    li $t1, 1
    add $t2, $t0, $t1
    sw $t2, -40($fp)
    lw $t0, -4($fp)
    lw $t1, -40($fp)
    sll $t1, $t1, 2
    add $t0, $t0, $t1
    lw $t2, 0($t0)
    sw $t2, -28($fp)
    lw $t0, -28($fp)
    sw $t0, -16($fp)
    lw $t0, -16($fp)
    lw $t1, -36($fp)
    blt $t0, $t1, quicksort_loop1
quicksort_loop2:
    lw $t0, -44($fp)
    li $t1, 1
    sub $t2, $t0, $t1
    sw $t2, -44($fp)
    lw $t0, -4($fp)
    lw $t1, -44($fp)
    sll $t1, $t1, 2
    add $t0, $t0, $t1
    lw $t2, 0($t0)
    sw $t2, -28($fp)
    lw $t0, -28($fp)
    sw $t0, -20($fp)
    lw $t0, -20($fp)
    lw $t1, -36($fp)
    bgt $t0, $t1, quicksort_loop2
    lw $t0, -40($fp)
    lw $t1, -44($fp)
    bge $t0, $t1, quicksort_exit0
    lw $t2, -16($fp)
    lw $t0, -4($fp)
    lw $t1, -44($fp)
    sll $t1, $t1, 2
    add $t0, $t0, $t1
    sw $t2, 0($t0)
    lw $t2, -20($fp)
    lw $t0, -4($fp)
    lw $t1, -40($fp)
    sll $t1, $t1, 2
    add $t0, $t0, $t1
    sw $t2, 0($t0)
    j quicksort_loop0
quicksort_exit0:
    lw $t0, -44($fp)
    li $t1, 1
    add $t2, $t0, $t1
    sw $t2, -24($fp)
    addi $sp, $sp, -32
    sw $t0, 0($sp)
    sw $t1, 4($sp)
    sw $t2, 8($sp)
    sw $t3, 12($sp)
    sw $t4, 16($sp)
    sw $t5, 20($sp)
    sw $t6, 24($sp)
    sw $t7, 28($sp)
    lw $a0, -4($fp)
    lw $a1, -8($fp)
    lw $a2, -44($fp)
    jal quicksort
    lw $t0, 0($sp)
    lw $t1, 4($sp)
    lw $t2, 8($sp)
    lw $t3, 12($sp)
    lw $t4, 16($sp)
    lw $t5, 20($sp)
    lw $t6, 24($sp)
    lw $t7, 28($sp)
    addi $sp, $sp, 32
    lw $t0, -44($fp)
    li $t1, 1
    add $t2, $t0, $t1
    sw $t2, -44($fp)
    addi $sp, $sp, -32
    sw $t0, 0($sp)
    sw $t1, 4($sp)
    sw $t2, 8($sp)
    sw $t3, 12($sp)
    sw $t4, 16($sp)
    sw $t5, 20($sp)
    sw $t6, 24($sp)
    sw $t7, 28($sp)
    lw $a0, -4($fp)
    lw $a1, -44($fp)
    lw $a2, -12($fp)
    jal quicksort
    lw $t0, 0($sp)
    lw $t1, 4($sp)
    lw $t2, 8($sp)
    lw $t3, 12($sp)
    lw $t4, 16($sp)
    lw $t5, 20($sp)
    lw $t6, 24($sp)
    lw $t7, 28($sp)
    addi $sp, $sp, 32
quicksort_end:
    addi $sp, $sp, 48
    lw $fp, 0($sp)
    addi $sp, $sp, 4
    jr $ra
