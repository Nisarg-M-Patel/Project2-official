package main.java.mips.backend;

import ir.*;
import ir.datatype.IRArrayType;
import ir.operand.*;
import java.io.PrintStream;
import java.util.*;

public class MIPSNaiveAllocator {
    private PrintStream output;
    
    public MIPSNaiveAllocator(PrintStream output) {
        this.output = output;
    }
    
    public void generateProgram(IRProgram program) {
        for (IRFunction func : program.functions) {
            if (func.name.equals("main")) {
                generateFunction(func);
                break;
            }
        }
        
        for (IRFunction func : program.functions) {
            if (!func.name.equals("main")) {
                generateFunction(func);
            }
        }
    }
    
    private void generateFunction(IRFunction func) {
        Map<String, Integer> stackOffsets = new HashMap<>();
        Map<String, String> labelMap = new HashMap<>();
        String funcPrefix = func.name + "_";
        int offset = 4;
        
        // Build label mapping to make labels unique
        for (IRInstruction instr : func.instructions) {
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                String originalLabel = ((IRLabelOperand)instr.operands[0]).getName();
                String uniqueLabel = funcPrefix + originalLabel;
                labelMap.put(originalLabel, uniqueLabel);
            }
        }
        
        // Set up parameter offsets
        Set<String> paramNames = new HashSet<>();
        for (IRVariableOperand param : func.parameters) {
            paramNames.add(param.getName());
            stackOffsets.put(param.getName(), offset);
            offset += 4;
        }
        
        // Set up variable offsets
        for (IRVariableOperand var : func.variables) {
            if (paramNames.contains(var.getName())) continue;
            
            if (var.type instanceof IRArrayType) {
                IRArrayType arrayType = (IRArrayType) var.type;
                stackOffsets.put(var.getName(), offset);
                offset += arrayType.getSize() * 4;
            } else {
                stackOffsets.put(var.getName(), offset);
                offset += 4;
            }
        }
        
        // Function prologue
        if (func.name.equals("main")) {
            output.println(".text");
        }
        
        output.println(func.name + ":");
        output.println("    addi $sp, $sp, -4");
        output.println("    sw $fp, 0($sp)");
        output.println("    move $fp, $sp");
        output.println("    addi $sp, $sp, -" + offset);
        
        // Allocate arrays on heap using sbrk
        for (IRVariableOperand var : func.variables) {
            if (var.type instanceof IRArrayType && !paramNames.contains(var.getName())) {
                IRArrayType arrayType = (IRArrayType) var.type;
                output.println("    li $v0, 9");
                output.println("    li $a0, " + (arrayType.getSize() * 4));
                output.println("    syscall");
                output.println("    sw $v0, -" + stackOffsets.get(var.getName()) + "($fp)");
            }
        }
        
        // Generate instructions
        for (IRInstruction instr : func.instructions) {
            generateInstruction(instr, stackOffsets, labelMap);
        }
        
        // Function epilogue
        output.println("    addi $sp, $sp, " + offset);
        output.println("    lw $fp, 0($sp)");
        output.println("    addi $sp, $sp, 4");
        
        if (func.name.equals("main")) {
            output.println("    li $v0, 10");
            output.println("    syscall");
        } else {
            output.println("    jr $ra");
        }
    }
    
    private void generateInstruction(IRInstruction instr, Map<String, Integer> stackOffsets, Map<String, String> labelMap) {
        switch (instr.opCode) {
            case LABEL:
                String originalLabel = ((IRLabelOperand)instr.operands[0]).getName();
                output.println(labelMap.get(originalLabel) + ":");
                break;
            case ASSIGN:
                generateAssign(instr, stackOffsets);
                break;
            case ADD:
            case SUB:
            case MULT:
            case DIV:
            case AND:
            case OR:
                generateBinaryOp(instr, stackOffsets);
                break;
            case GOTO:
                String gotoLabel = ((IRLabelOperand)instr.operands[0]).getName();
                output.println("    j " + labelMap.get(gotoLabel));
                break;
            case BREQ:
            case BRNEQ:
            case BRLT:
            case BRGT:
            case BRGEQ:
                generateBranch(instr, stackOffsets, labelMap);
                break;
            case CALL:
            case CALLR:
                generateCall(instr, stackOffsets);
                break;
            case RETURN:
                generateReturn(instr, stackOffsets);
                break;
            case ARRAY_LOAD:
                generateArrayLoad(instr, stackOffsets);
                break;
            case ARRAY_STORE:
                generateArrayStore(instr, stackOffsets);
                break;
        }
    }
    
    private void generateAssign(IRInstruction instr, Map<String, Integer> stackOffsets) {
        if (instr.operands.length == 3) {
            // Array assignment: assign, A, size, value
            return; // Skip for now, not commonly used
        }
        
        IROperand dest = instr.operands[0];
        IROperand src = instr.operands[1];
        
        loadOperand(src, "$t0", stackOffsets);
        storeOperand(dest, "$t0", stackOffsets);
    }
    
    private void generateBinaryOp(IRInstruction instr, Map<String, Integer> stackOffsets) {
        IROperand dest = instr.operands[0];
        IROperand op1 = instr.operands[1];
        IROperand op2 = instr.operands[2];
        
        loadOperand(op1, "$t0", stackOffsets);
        loadOperand(op2, "$t1", stackOffsets);
        
        String mipsOp = getMIPSOp(instr.opCode);
        output.println("    " + mipsOp + " $t2, $t0, $t1");
        
        storeOperand(dest, "$t2", stackOffsets);
    }
    
    private void generateBranch(IRInstruction instr, Map<String, Integer> stackOffsets, Map<String, String> labelMap) {
        String originalLabel = ((IRLabelOperand)instr.operands[0]).getName();
        String label = labelMap.get(originalLabel);
        IROperand op1 = instr.operands[1];
        IROperand op2 = instr.operands[2];
        
        loadOperand(op1, "$t0", stackOffsets);
        loadOperand(op2, "$t1", stackOffsets);
        
        String mipsOp = getMIPSBranch(instr.opCode);
        output.println("    " + mipsOp + " $t0, $t1, " + label);
    }
    
    private void generateCall(IRInstruction instr, Map<String, Integer> stackOffsets) {
        int startIdx = (instr.opCode == IRInstruction.OpCode.CALL) ? 1 : 2;
        int funcIdx = (instr.opCode == IRInstruction.OpCode.CALL) ? 0 : 1;
        String funcName = ((IRFunctionOperand)instr.operands[funcIdx]).getName();
        
        // Handle intrinsic functions
        if (funcName.equals("geti")) {
            output.println("    li $v0, 5");
            output.println("    syscall");
            if (instr.opCode == IRInstruction.OpCode.CALLR) {
                storeOperand(instr.operands[0], "$v0", stackOffsets);
            }
            return;
        }
        
        if (funcName.equals("getf")) {
            output.println("    li $v0, 6");
            output.println("    syscall");
            if (instr.opCode == IRInstruction.OpCode.CALLR) {
                storeOperand(instr.operands[0], "$f0", stackOffsets);
            }
            return;
        }
        
        if (funcName.equals("getc")) {
            output.println("    li $v0, 12");
            output.println("    syscall");
            if (instr.opCode == IRInstruction.OpCode.CALLR) {
                storeOperand(instr.operands[0], "$v0", stackOffsets);
            }
            return;
        }
        
        if (funcName.equals("puti")) {
            loadOperand(instr.operands[startIdx], "$a0", stackOffsets);
            output.println("    li $v0, 1");
            output.println("    syscall");
            return;
        }
        
        if (funcName.equals("putf")) {
            loadOperand(instr.operands[startIdx], "$f12", stackOffsets);
            output.println("    li $v0, 2");
            output.println("    syscall");
            return;
        }
        
        if (funcName.equals("putc")) {
            loadOperand(instr.operands[startIdx], "$a0", stackOffsets);
            output.println("    li $v0, 11");
            output.println("    syscall");
            return;
        }
        
        // Regular function call - save $t0-$t7 (8 registers)
        output.println("    addi $sp, $sp, -32");
        output.println("    sw $t0, 0($sp)");
        output.println("    sw $t1, 4($sp)");
        output.println("    sw $t2, 8($sp)");
        output.println("    sw $t3, 12($sp)");
        output.println("    sw $t4, 16($sp)");
        output.println("    sw $t5, 20($sp)");
        output.println("    sw $t6, 24($sp)");
        output.println("    sw $t7, 28($sp)");
        
        // Load arguments into $a0-$a3, rest on stack
        int argCount = instr.operands.length - startIdx;
        for (int i = 0; i < argCount; i++) {
            if (i < 4) {
                loadOperand(instr.operands[startIdx + i], "$a" + i, stackOffsets);
            } else {
                loadOperand(instr.operands[startIdx + i], "$t8", stackOffsets);
                output.println("    addi $sp, $sp, -4");
                output.println("    sw $t8, 0($sp)");
            }
        }
        
        output.println("    jal " + funcName);
        
        // Clean up extra arguments from stack
        if (argCount > 4) {
            output.println("    addi $sp, $sp, " + ((argCount - 4) * 4));
        }
        
        // Restore $t0-$t7
        output.println("    lw $t0, 0($sp)");
        output.println("    lw $t1, 4($sp)");
        output.println("    lw $t2, 8($sp)");
        output.println("    lw $t3, 12($sp)");
        output.println("    lw $t4, 16($sp)");
        output.println("    lw $t5, 20($sp)");
        output.println("    lw $t6, 24($sp)");
        output.println("    lw $t7, 28($sp)");
        output.println("    addi $sp, $sp, 32");
        
        if (instr.opCode == IRInstruction.OpCode.CALLR) {
            storeOperand(instr.operands[0], "$v0", stackOffsets);
        }
    }
    
    private void generateReturn(IRInstruction instr, Map<String, Integer> stackOffsets) {
        loadOperand(instr.operands[0], "$v0", stackOffsets);
        // Don't generate jr $ra here - it's in the epilogue
    }
    
    private void generateArrayLoad(IRInstruction instr, Map<String, Integer> stackOffsets) {
        IROperand dest = instr.operands[0];
        IROperand array = instr.operands[1];
        IROperand index = instr.operands[2];
        
        // Load array base address (heap pointer)
        int arrayOffset = stackOffsets.get(((IRVariableOperand)array).getName());
        output.println("    lw $t0, -" + arrayOffset + "($fp)");
        
        // Load index and compute offset
        loadOperand(index, "$t1", stackOffsets);
        output.println("    sll $t1, $t1, 2");
        
        // Add offset to base address
        output.println("    add $t0, $t0, $t1");
        output.println("    lw $t2, 0($t0)");
        
        storeOperand(dest, "$t2", stackOffsets);
    }
    
    private void generateArrayStore(IRInstruction instr, Map<String, Integer> stackOffsets) {
        IROperand value = instr.operands[0];
        IROperand array = instr.operands[1];
        IROperand index = instr.operands[2];
        
        // Load value to store
        loadOperand(value, "$t2", stackOffsets);
        
        // Load array base address (heap pointer)
        int arrayOffset = stackOffsets.get(((IRVariableOperand)array).getName());
        output.println("    lw $t0, -" + arrayOffset + "($fp)");
        
        // Load index and compute offset
        loadOperand(index, "$t1", stackOffsets);
        output.println("    sll $t1, $t1, 2");
        
        // Add offset to base address
        output.println("    add $t0, $t0, $t1");
        output.println("    sw $t2, 0($t0)");
    }
    
    private void loadOperand(IROperand operand, String reg, Map<String, Integer> stackOffsets) {
        if (operand instanceof IRConstantOperand) {
            output.println("    li " + reg + ", " + ((IRConstantOperand)operand).getValueString());
        } else if (operand instanceof IRVariableOperand) {
            int offset = stackOffsets.get(((IRVariableOperand)operand).getName());
            output.println("    lw " + reg + ", -" + offset + "($fp)");
        }
    }
    
    private void storeOperand(IROperand operand, String reg, Map<String, Integer> stackOffsets) {
        if (operand instanceof IRVariableOperand) {
            int offset = stackOffsets.get(((IRVariableOperand)operand).getName());
            output.println("    sw " + reg + ", -" + offset + "($fp)");
        }
    }
    
    private String getMIPSOp(IRInstruction.OpCode opCode) {
        switch (opCode) {
            case ADD: return "add";
            case SUB: return "sub";
            case MULT: return "mul";
            case DIV: return "div";
            case AND: return "and";
            case OR: return "or";
            default: return "nop";
        }
    }
    
    private String getMIPSBranch(IRInstruction.OpCode opCode) {
        switch (opCode) {
            case BREQ: return "beq";
            case BRNEQ: return "bne";
            case BRLT: return "blt";
            case BRGT: return "bgt";
            case BRGEQ: return "bge";
            default: return "nop";
        }
    }
}