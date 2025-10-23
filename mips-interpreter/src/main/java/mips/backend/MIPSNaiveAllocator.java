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
        output.println(".text");
        
        for (IRFunction func : program.functions) {
            if (func.name.equals("main")) {
                generateFunction(func);
                output.println("    li $v0, 10");
                output.println("    syscall");
                output.println();
                break;
            }
        }
        
        for (IRFunction func : program.functions) {
            if (!func.name.equals("main")) {
                generateFunction(func);
                output.println("    jr $ra");
                output.println();
            }
        }
    }
    
    private void generateFunction(IRFunction func) {
        Map<String, Integer> stackOffsets = new HashMap<>();
        Map<String, String> labelMap = new HashMap<>();
        String funcPrefix = func.name + "_";
        int fp_offset = 4;
        
        Set<String> paramNames = new HashSet<>();
        int local_size = 0;
        int arg_size = 0;
        
        // Build label mapping
        for (IRInstruction instr : func.instructions) {
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                String originalLabel = ((IRLabelOperand)instr.operands[0]).getName();
                labelMap.put(originalLabel, funcPrefix + originalLabel);
            }
        }
        
        // Set up parameter offsets
        for (IRVariableOperand param : func.parameters) {
            paramNames.add(param.getName());
            stackOffsets.put(param.getName(), fp_offset);
            fp_offset += 4;
            arg_size += 4;
        }
        
        // Set up variable offsets
        for (IRVariableOperand var : func.variables) {
            if (paramNames.contains(var.getName())) continue;
            local_size += 4;
            stackOffsets.put(var.getName(), fp_offset);
            fp_offset += 4;
        }
        
        local_size += arg_size;
        
        // Function prologue
        output.println(func.name + ":");
        output.println("    addi $sp, $sp, -4");
        output.println("    sw   $fp, 0($sp)");
        output.println("    move $fp, $sp");
        
        // Store parameters
        int argOffset = (func.parameters.size() - 4) * 4;
        for (int i = 0; i < func.parameters.size(); i++) {
            int offset = stackOffsets.get(func.parameters.get(i).getName());
            if (i < 4) {
                output.println("    sw $a" + i + ", -" + offset + "($fp)");
            } else {
                output.println("    lw $t0, " + argOffset + "($fp)");  
                argOffset -= 4;
                output.println("    sw $t0, -" + offset + "($fp)");
            }
        }
        
        output.println("    addi $sp, $sp, -" + local_size);
        output.println("    addi $sp, $sp, -4");
        output.println("    sw   $ra, 0($sp)");
        
        // Allocate arrays on heap
        for (IRVariableOperand var : func.variables) {
            if (var.type instanceof IRArrayType && !paramNames.contains(var.getName())) {
                IRArrayType arr = (IRArrayType) var.type;
                output.println("    li $v0, 9");
                output.println("    li $a0, " + (arr.getSize() * 4));
                output.println("    syscall");
                output.println("    sw $v0, -" + stackOffsets.get(var.getName()) + "($fp)");
            }
        }
        
        // Generate instructions
        for (IRInstruction instr : func.instructions) {
            generateInstruction(func, instr, stackOffsets, labelMap);
        }
        
        // Function epilogue
        output.println("    lw   $ra, 0($sp)");
        output.println("    addi $sp, $sp, 4");
        output.println("    addi $sp, $sp, " + local_size);
        output.println("    lw   $fp, 0($sp)");
        output.println("    addi $sp, $sp, 4");
    }
    
    private void generateInstruction(IRFunction func, IRInstruction instr, 
                                     Map<String, Integer> stackOffsets, 
                                     Map<String, String> labelMap) {
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
        IROperand dest = instr.operands[0];
        IROperand src = instr.operands[1];
        
        String destName = dest.toString();
        int destOff = stackOffsets.get(destName);
        String srcValue = src.toString();
        
        if (isNumeric(srcValue)) {
            output.println("    li $t1, " + srcValue);
        } else {
            int srcOff = stackOffsets.get(srcValue);
            output.println("    lw $t1, -" + srcOff + "($fp)");
        }
        output.println("    sw $t1, -" + destOff + "($fp)");
    }
    
    private void generateBinaryOp(IRInstruction instr, Map<String, Integer> stackOffsets) {
        String res = instr.operands[0].toString();
        int resOff = stackOffsets.get(res);
        String op1 = instr.operands[1].toString();
        int op1Off = stackOffsets.get(op1);
        String op2 = instr.operands[2].toString();
        
        output.println("    lw $t1, -" + op1Off + "($fp)");
        
        if (isNumeric(op2)) {
            output.println("    li $t2, " + op2);
        } else {
            int op2Off = stackOffsets.get(op2);
            output.println("    lw $t2, -" + op2Off + "($fp)");
        }
        
        String mipsOp = getMIPSOp(instr.opCode);
        output.println("    " + mipsOp + " $t0, $t1, $t2");
        output.println("    sw $t0, -" + resOff + "($fp)");
    }
    
    private void generateBranch(IRInstruction instr, Map<String, Integer> stackOffsets, 
                                Map<String, String> labelMap) {
        String lbl = instr.operands[0].toString();
        String cmp1 = instr.operands[1].toString();
        String cmp2 = instr.operands[2].toString();
        
        if (isNumeric(cmp1)) {
            output.println("    li $t0, " + cmp1);
        } else {
            int cmp1Off = stackOffsets.get(cmp1);
            output.println("    lw $t0, -" + cmp1Off + "($fp)");
        }
        
        if (isNumeric(cmp2)) {
            output.println("    li $t1, " + cmp2);
        } else {
            int cmp2Off = stackOffsets.get(cmp2);
            output.println("    lw $t1, -" + cmp2Off + "($fp)");
        }
        
        String branchOp = getMIPSBranch(instr.opCode);
        output.println("    " + branchOp + " $t0, $t1, " + labelMap.get(lbl));
    }
    
    private void generateReturn(IRInstruction instr, Map<String, Integer> stackOffsets) {
        String retValue = instr.operands[0].toString();
        
        if (isNumeric(retValue)) {
            output.println("    li $v0, " + retValue);
        } else {
            int retOff = stackOffsets.get(retValue);
            output.println("    lw $v0, -" + retOff + "($fp)");
        }
        output.println("    jr $ra");
    }
    
    private void generateCall(IRInstruction instr, Map<String, Integer> stackOffsets) {
        boolean isCallr = instr.opCode == IRInstruction.OpCode.CALLR;
        String dest = isCallr ? instr.operands[0].toString() : null;
        String funcName = isCallr ? ((IRFunctionOperand)instr.operands[1]).getName() 
                                  : ((IRFunctionOperand)instr.operands[0]).getName();
        
        List<String> args = new ArrayList<>();
        int startIdx = isCallr ? 2 : 1;
        for (int i = startIdx; i < instr.operands.length; i++) {
            args.add(instr.operands[i].toString());
        }
        
        // Handle intrinsics
        if (funcName.equals("geti")) {
            output.println("    li $v0, 5");
            output.println("    syscall");
            if (dest != null) {
                output.println("    move $t0, $v0");
                output.println("    sw $t0, -" + stackOffsets.get(dest) + "($fp)");
            }
            return;
        }
        
        if (funcName.equals("getc")) {
            output.println("    li $v0, 12");
            output.println("    syscall");
            if (dest != null) {
                output.println("    move $t0, $v0");
                output.println("    sw $t0, -" + stackOffsets.get(dest) + "($fp)");
            }
            return;
        }
        
        if (funcName.equals("puti")) {
            if (isNumeric(args.get(0))) {
                output.println("    li $a0, " + args.get(0));
            } else {
                output.println("    lw $a0, -" + stackOffsets.get(args.get(0)) + "($fp)");
            }
            output.println("    li $v0, 1");
            output.println("    syscall");
            return;
        }
        
        if (funcName.equals("putc")) {
            if (isNumeric(args.get(0))) {
                output.println("    li $a0, " + args.get(0));
            } else {
                output.println("    lw $a0, -" + stackOffsets.get(args.get(0)) + "($fp)");
            }
            output.println("    li $v0, 11");
            output.println("    syscall");
            return;
        }
        
        // Regular function call
        int stackArgOffset = 0;
        for (int i = 0; i < args.size(); i++) {
            Integer offset = stackOffsets.getOrDefault(args.get(i), null);
            if (i < 4) {
                if (offset == null) {
                    output.println("    li $a" + i + ", " + args.get(i));
                } else {
                    output.println("    lw $a" + i + ", -" + offset + "($fp)");
                }
            } else {
                int stackOffset = (i - 3) * 4;
                if (offset == null) {
                    output.println("    li $t0, " + args.get(i));
                    output.println("    sw $t0, -" + stackOffset + "($sp)");
                } else {
                    output.println("    lw $t0, -" + offset + "($fp)");
                    output.println("    sw $t0, -" + stackOffset + "($sp)");
                }
                stackArgOffset += 4;
            }
        }
        
        if (stackArgOffset > 0) {
            output.println("    addi $sp, $sp, -" + stackArgOffset);
        }
        
        output.println("    jal " + funcName);
        
        if (dest != null) {
            output.println("    move $t0, $v0");
            output.println("    sw $t0, -" + stackOffsets.get(dest) + "($fp)");
        }
        
        if (stackArgOffset > 0) {
            output.println("    addi $sp, $sp, " + stackArgOffset);
        }
    }
    
    private void generateArrayLoad(IRInstruction instr, Map<String, Integer> stackOffsets) {
        String loadDest = instr.operands[0].toString();
        int arrDestOff = stackOffsets.get(loadDest);
        String loadArr = instr.operands[1].toString();
        int arrAddrOff = stackOffsets.get(loadArr);
        String loadIdx = instr.operands[2].toString();
        
        if (isNumeric(loadIdx)) {
            output.println("    li $t2, " + loadIdx);
        } else {
            int loadIdxOff = stackOffsets.get(loadIdx);
            output.println("    lw $t2, -" + loadIdxOff + "($fp)");
        }
        output.println("    sll $t2, $t2, 2");
        output.println("    lw $t1, -" + arrAddrOff + "($fp)");
        output.println("    add $t2, $t2, $t1");
        output.println("    lw $t1, 0($t2)");
        output.println("    sw $t1, -" + arrDestOff + "($fp)");
    }
    
    private void generateArrayStore(IRInstruction instr, Map<String, Integer> stackOffsets) {
        String val = instr.operands[0].toString();
        String arr = instr.operands[1].toString();
        int arrAddrOff = stackOffsets.get(arr);
        String index = instr.operands[2].toString();
        
        if (isNumeric(index)) {
            output.println("    li $t2, " + index);
        } else {
            int idxOff = stackOffsets.get(index);
            output.println("    lw $t2, -" + idxOff + "($fp)");
        }
        
        if (isNumeric(val)) {
            output.println("    li $t0, " + val);
        } else {
            int valOff = stackOffsets.get(val);
            output.println("    lw $t0, -" + valOff + "($fp)");
        }
        
        output.println("    lw $t1, -" + arrAddrOff + "($fp)");
        output.println("    sll $t2, $t2, 2");
        output.println("    add $t1, $t2, $t1");
        output.println("    sw $t0, 0($t1)");
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
    
    private boolean isNumeric(String str) {
        return str.matches("-?\\d+");
    }
}