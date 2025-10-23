package main.java.mips.backend;

import ir.*;
import ir.datatype.IRArrayType;
import ir.operand.*;
import java.io.PrintStream;
import java.util.*;

public class MIPSGreedyAllocator {
    private PrintStream output;
    
    public MIPSGreedyAllocator(PrintStream output) {
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
        // Build basic blocks
        List<BasicBlock> blocks = buildBasicBlocks(func);
        
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
        
        // Generate code for each block
        for (BasicBlock block : blocks) {
            generateBlock(block, stackOffsets, labelMap);
        }
        
        // Function epilogue
        output.println("    lw   $ra, 0($sp)");
        output.println("    addi $sp, $sp, 4");
        output.println("    addi $sp, $sp, " + local_size);
        output.println("    lw   $fp, 0($sp)");
        output.println("    addi $sp, $sp, 4");
    }
    
    private void generateBlock(BasicBlock block, Map<String, Integer> stackOffsets, 
                              Map<String, String> labelMap) {
        // Allocate registers for variables in this block
        Map<String, String> regAlloc = allocateRegistersForBlock(block);
        
        // Track which variables are currently in their allocated registers
        Set<String> inRegister = new HashSet<>();
        
        // Generate instructions
        for (IRInstruction instr : block.instructions) {
            generateInstruction(instr, stackOffsets, labelMap, regAlloc, inRegister);
        }
        
        // Store any variables still in registers at end of block
        // (only if block doesn't end with control transfer)
        if (!block.instructions.isEmpty()) {
            IRInstruction lastInstr = block.instructions.get(block.instructions.size() - 1);
            if (!endsWithControlTransfer(lastInstr)) {
                for (String var : inRegister) {
                    String reg = regAlloc.get(var);
                    if (stackOffsets.containsKey(var)) {
                        output.println("    sw " + reg + ", -" + stackOffsets.get(var) + "($fp)");
                    }
                }
            }
        }
    }
    
    private boolean endsWithControlTransfer(IRInstruction instr) {
        IRInstruction.OpCode op = instr.opCode;
        return op == IRInstruction.OpCode.GOTO ||
               op == IRInstruction.OpCode.RETURN ||
               op == IRInstruction.OpCode.BREQ ||
               op == IRInstruction.OpCode.BRNEQ ||
               op == IRInstruction.OpCode.BRLT ||
               op == IRInstruction.OpCode.BRGT ||
               op == IRInstruction.OpCode.BRGEQ;
    }
    
    private Map<String, String> allocateRegistersForBlock(BasicBlock block) {
        // Count uses of each variable in the block
        Map<String, Integer> useCounts = new HashMap<>();
        
        for (IRInstruction instr : block.instructions) {
            for (IROperand op : instr.operands) {
                if (op instanceof IRVariableOperand) {
                    String var = op.toString();
                    useCounts.put(var, useCounts.getOrDefault(var, 0) + 1);
                }
            }
        }
        
        // Sort by use count (most used first)
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(useCounts.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        // Allocate top 8 to registers
        String[] regs = {"$t0", "$t1", "$t2", "$t3", "$t4", "$t5", "$t6", "$t7"};
        Map<String, String> allocation = new HashMap<>();
        
        for (int i = 0; i < Math.min(sorted.size(), regs.length); i++) {
            allocation.put(sorted.get(i).getKey(), regs[i]);
        }
        
        return allocation;
    }
    
    private void generateInstruction(IRInstruction instr, Map<String, Integer> stackOffsets,
                                    Map<String, String> labelMap, Map<String, String> regAlloc,
                                    Set<String> inRegister) {
        switch (instr.opCode) {
            case LABEL:
                String originalLabel = ((IRLabelOperand)instr.operands[0]).getName();
                output.println(labelMap.get(originalLabel) + ":");
                // Clear inRegister set because we might jump here from anywhere
                inRegister.clear();
                break;
            case ASSIGN:
                genAssign(instr, stackOffsets, regAlloc, inRegister);
                break;
            case ADD:
            case SUB:
            case MULT:
            case DIV:
            case AND:
            case OR:
                genBinaryOp(instr, stackOffsets, regAlloc, inRegister);
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
                genBranch(instr, stackOffsets, labelMap, regAlloc, inRegister);
                break;
            case CALL:
            case CALLR:
                genCall(instr, stackOffsets, regAlloc, inRegister);
                break;
            case RETURN:
                genReturn(instr, stackOffsets, regAlloc, inRegister);
                break;
            case ARRAY_LOAD:
                genArrayLoad(instr, stackOffsets, regAlloc, inRegister);
                break;
            case ARRAY_STORE:
                genArrayStore(instr, stackOffsets, regAlloc, inRegister);
                break;
        }
    }
    
    // Helper: Get variable into a register, loading if necessary
    private String ensureInRegister(String var, Map<String, String> regAlloc, 
                                   Map<String, Integer> stackOffsets, 
                                   Set<String> inRegister, String tempReg) {
        if (isNumeric(var)) {
            output.println("    li " + tempReg + ", " + var);
            return tempReg;
        }
        
        String reg = regAlloc.get(var);
        if (reg != null) {
            // Variable has an allocated register
            if (!inRegister.contains(var)) {
                // Need to load it
                output.println("    lw " + reg + ", -" + stackOffsets.get(var) + "($fp)");
                inRegister.add(var);
            }
            return reg;
        } else {
            // No allocated register - use temp and load each time (naive behavior)
            output.println("    lw " + tempReg + ", -" + stackOffsets.get(var) + "($fp)");
            return tempReg;
        }
    }
    
    private void genAssign(IRInstruction instr, Map<String, Integer> stackOffsets,
                          Map<String, String> regAlloc, Set<String> inRegister) {
        String dest = instr.operands[0].toString();
        String src = instr.operands[1].toString();
        
        String destReg = regAlloc.get(dest);
        
        if (destReg != null) {
            // Destination has allocated register
            if (isNumeric(src)) {
                output.println("    li " + destReg + ", " + src);
            } else {
                String srcReg = ensureInRegister(src, regAlloc, stackOffsets, inRegister, "$t8");
                if (!destReg.equals(srcReg)) {
                    output.println("    move " + destReg + ", " + srcReg);
                }
            }
            inRegister.add(dest);
        } else {
            // Destination not allocated - store immediately (naive behavior)
            if (isNumeric(src)) {
                output.println("    li $t9, " + src);
            } else {
                String srcReg = ensureInRegister(src, regAlloc, stackOffsets, inRegister, "$t8");
                output.println("    move $t9, " + srcReg);
            }
            output.println("    sw $t9, -" + stackOffsets.get(dest) + "($fp)");
        }
    }
    
    private void genBinaryOp(IRInstruction instr, Map<String, Integer> stackOffsets,
                            Map<String, String> regAlloc, Set<String> inRegister) {
        String dest = instr.operands[0].toString();
        String op1 = instr.operands[1].toString();
        String op2 = instr.operands[2].toString();
        
        String destReg = regAlloc.get(dest);
        
        // Get operands into registers
        String op1Reg = ensureInRegister(op1, regAlloc, stackOffsets, inRegister, "$t8");
        String op2Reg = ensureInRegister(op2, regAlloc, stackOffsets, inRegister, "$t9");
        
        if (destReg != null) {
            // Destination has allocated register
            String mipsOp = getMIPSOp(instr.opCode);
            output.println("    " + mipsOp + " " + destReg + ", " + op1Reg + ", " + op2Reg);
            inRegister.add(dest);
        } else {
            // Destination not allocated - store immediately
            String mipsOp = getMIPSOp(instr.opCode);
            output.println("    " + mipsOp + " $t7, " + op1Reg + ", " + op2Reg);
            output.println("    sw $t7, -" + stackOffsets.get(dest) + "($fp)");
        }
    }
    
    private void genBranch(IRInstruction instr, Map<String, Integer> stackOffsets,
                          Map<String, String> labelMap, Map<String, String> regAlloc,
                          Set<String> inRegister) {
        String lbl = instr.operands[0].toString();
        String cmp1 = instr.operands[1].toString();
        String cmp2 = instr.operands[2].toString();
        
        String cmp1Reg = ensureInRegister(cmp1, regAlloc, stackOffsets, inRegister, "$t8");
        String cmp2Reg = ensureInRegister(cmp2, regAlloc, stackOffsets, inRegister, "$t9");
        
        String branchOp = getMIPSBranch(instr.opCode);
        output.println("    " + branchOp + " " + cmp1Reg + ", " + cmp2Reg + ", " + labelMap.get(lbl));
    }
    
    private void genReturn(IRInstruction instr, Map<String, Integer> stackOffsets,
                          Map<String, String> regAlloc, Set<String> inRegister) {
        String retValue = instr.operands[0].toString();
        
        if (isNumeric(retValue)) {
            output.println("    li $v0, " + retValue);
        } else {
            String retReg = ensureInRegister(retValue, regAlloc, stackOffsets, inRegister, "$t8");
            output.println("    move $v0, " + retReg);
        }
        output.println("    jr $ra");
    }
    
    private void genCall(IRInstruction instr, Map<String, Integer> stackOffsets,
                        Map<String, String> regAlloc, Set<String> inRegister) {
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
                String destReg = regAlloc.get(dest);
                if (destReg != null) {
                    output.println("    move " + destReg + ", $v0");
                    inRegister.add(dest);
                } else {
                    output.println("    sw $v0, -" + stackOffsets.get(dest) + "($fp)");
                }
            }
            return;
        }
        
        if (funcName.equals("puti")) {
            String argReg = ensureInRegister(args.get(0), regAlloc, stackOffsets, inRegister, "$t8");
            output.println("    move $a0, " + argReg);
            output.println("    li $v0, 1");
            output.println("    syscall");
            return;
        }
        
        if (funcName.equals("putc")) {
            String argReg = ensureInRegister(args.get(0), regAlloc, stackOffsets, inRegister, "$t8");
            output.println("    move $a0, " + argReg);
            output.println("    li $v0, 11");
            output.println("    syscall");
            return;
        }
        
        // Regular function call
        int stackArgOffset = 0;
        for (int i = 0; i < args.size(); i++) {
            if (i < 4) {
                String argReg = ensureInRegister(args.get(i), regAlloc, stackOffsets, inRegister, "$t8");
                output.println("    move $a" + i + ", " + argReg);
            } else {
                String argReg = ensureInRegister(args.get(i), regAlloc, stackOffsets, inRegister, "$t8");
                int stackOffset = (i - 3) * 4;
                output.println("    sw " + argReg + ", -" + stackOffset + "($sp)");
                stackArgOffset += 4;
            }
        }
        
        if (stackArgOffset > 0) {
            output.println("    addi $sp, $sp, -" + stackArgOffset);
        }
        
        output.println("    jal " + funcName);
        
        // After call, registers may be clobbered - clear tracking
        inRegister.clear();
        
        if (dest != null) {
            String destReg = regAlloc.get(dest);
            if (destReg != null) {
                output.println("    move " + destReg + ", $v0");
                inRegister.add(dest);
            } else {
                output.println("    sw $v0, -" + stackOffsets.get(dest) + "($fp)");
            }
        }
        
        if (stackArgOffset > 0) {
            output.println("    addi $sp, $sp, " + stackArgOffset);
        }
    }
    
    private void genArrayLoad(IRInstruction instr, Map<String, Integer> stackOffsets,
                             Map<String, String> regAlloc, Set<String> inRegister) {
        String dest = instr.operands[0].toString();
        String arr = instr.operands[1].toString();
        String idx = instr.operands[2].toString();
        
        String idxReg = ensureInRegister(idx, regAlloc, stackOffsets, inRegister, "$t8");
        output.println("    sll $t9, " + idxReg + ", 2");
        output.println("    lw $t8, -" + stackOffsets.get(arr) + "($fp)");
        output.println("    add $t9, $t9, $t8");
        
        String destReg = regAlloc.get(dest);
        if (destReg != null) {
            output.println("    lw " + destReg + ", 0($t9)");
            inRegister.add(dest);
        } else {
            output.println("    lw $t8, 0($t9)");
            output.println("    sw $t8, -" + stackOffsets.get(dest) + "($fp)");
        }
    }
    
    private void genArrayStore(IRInstruction instr, Map<String, Integer> stackOffsets,
                              Map<String, String> regAlloc, Set<String> inRegister) {
        String val = instr.operands[0].toString();
        String arr = instr.operands[1].toString();
        String idx = instr.operands[2].toString();
        
        String idxReg = ensureInRegister(idx, regAlloc, stackOffsets, inRegister, "$t8");
        String valReg = ensureInRegister(val, regAlloc, stackOffsets, inRegister, "$t9");
        
        output.println("    lw $t7, -" + stackOffsets.get(arr) + "($fp)");
        output.println("    sll $t6, " + idxReg + ", 2");
        output.println("    add $t7, $t6, $t7");
        output.println("    sw " + valReg + ", 0($t7)");
    }
    
    // Helper methods
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
    
    // Basic block construction
    private List<BasicBlock> buildBasicBlocks(IRFunction func) {
        List<BasicBlock> blocks = new ArrayList<>();
        Set<Integer> leaders = new HashSet<>();
        
        leaders.add(0);
        
        for (int i = 0; i < func.instructions.size(); i++) {
            IRInstruction instr = func.instructions.get(i);
            
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                leaders.add(i);
            }
            
            if (isBranchOrJump(instr) && i + 1 < func.instructions.size()) {
                leaders.add(i + 1);
            }
        }
        
        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        Collections.sort(sortedLeaders);
        
        for (int i = 0; i < sortedLeaders.size(); i++) {
            BasicBlock block = new BasicBlock();
            int start = sortedLeaders.get(i);
            int end = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) : func.instructions.size();
            
            for (int j = start; j < end; j++) {
                block.instructions.add(func.instructions.get(j));
            }
            
            if (!block.instructions.isEmpty()) {
                blocks.add(block);
            }
        }
        
        return blocks;
    }
    
    private boolean isBranchOrJump(IRInstruction instr) {
        IRInstruction.OpCode op = instr.opCode;
        return op == IRInstruction.OpCode.GOTO ||
               op == IRInstruction.OpCode.BREQ ||
               op == IRInstruction.OpCode.BRNEQ ||
               op == IRInstruction.OpCode.BRLT ||
               op == IRInstruction.OpCode.BRGT ||
               op == IRInstruction.OpCode.BRGEQ;
    }
    
    private static class BasicBlock {
        List<IRInstruction> instructions = new ArrayList<>();
    }
}