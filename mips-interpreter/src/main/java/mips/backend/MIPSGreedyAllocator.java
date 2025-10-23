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
        Map<Integer, MIPSInstructionWrapper> defMap = MIPSReachingDefinitions.computeDefReachMap(func);
        MIPSBasicBlock head = new MIPSBasicBlock(func, defMap);
        List<MIPSBasicBlock> blockList = head.getBlockList();

        debugBlocks(func, blockList);
        MIPSReachingDefinitions.computeUseSet(blockList, defMap);
        
        buildLiveSets(blockList);
        
        Map<String, Integer> stackOffsets = new HashMap<>();
        Map<String, String> labelMap = new HashMap<>();
        String funcPrefix = func.name + "_";
        int fp_offset = 4;
        
        Set<String> paramNames = new HashSet<>();
        int local_size = 0;
        int arg_size = 0;
        
        // Build label mapping for ALL instructions in function
        for (IRInstruction instr : func.instructions) {
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                String originalLabel = ((IRLabelOperand)instr.operands[0]).getName();
                labelMap.put(originalLabel, funcPrefix + originalLabel);
            }
        }
        
        for (IRVariableOperand param : func.parameters) {
            paramNames.add(param.getName());
            stackOffsets.put(param.getName(), fp_offset);
            fp_offset += 4;
            arg_size += 4;
        }
        
        for (IRVariableOperand var : func.variables) {
            if (paramNames.contains(var.getName())) continue;
            local_size += 4;
            stackOffsets.put(var.getName(), fp_offset);
            fp_offset += 4;
        }
        
        local_size += arg_size;
        
        output.println(func.name + ":");
        output.println("    addi $sp, $sp, -4");
        output.println("    sw   $fp, 0($sp)");
        output.println("    move $fp, $sp");
        
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
        
        for (IRVariableOperand var : func.variables) {
            if (var.type instanceof IRArrayType && !paramNames.contains(var.getName())) {
                IRArrayType arr = (IRArrayType) var.type;
                output.println("    li $v0, 9");
                output.println("    li $a0, " + (arr.getSize() * 4));
                output.println("    syscall");
                output.println("    sw $v0, -" + stackOffsets.get(var.getName()) + "($fp)");
            }
        }
        
        // Generate blocks in sorted order with ASM strings like working code
        Map<Integer, String> blockASMOrder = new HashMap<>();
        for (MIPSBasicBlock block : blockList) {
            String blockasm = generateBlockGreedyAsString(block, stackOffsets, labelMap);
            blockASMOrder.put(block.startLine, blockasm);
        }
        
        List<Integer> startLines = new ArrayList<>(blockASMOrder.keySet());
        Collections.sort(startLines);
        for (int line : startLines) {
            output.print(blockASMOrder.get(line));
        }
        
        output.println("    lw   $ra, 0($sp)");
        output.println("    addi $sp, $sp, 4");
        output.println("    addi $sp, $sp, " + local_size);
        output.println("    lw   $fp, 0($sp)");
        output.println("    addi $sp, $sp, 4");
    }
    
    private void buildLiveSets(List<MIPSBasicBlock> blockList) {
        List<MIPSBasicBlock> worklist = new ArrayList<>(blockList);
        while (!worklist.isEmpty()) {
            MIPSBasicBlock block = worklist.remove(0);
            Set<String> oldLiveIn = new HashSet<>(block.getLiveIn());
            block.computeLiveOut();
            Set<String> newLiveIn = block.computeLiveIn();
            if (!newLiveIn.equals(oldLiveIn)) {
                worklist.addAll(block.predecessors);
            }
        }
    }
    
    private String generateBlockGreedyAsString(MIPSBasicBlock block, Map<String, Integer> stackOffsets, 
                                               Map<String, String> labelMap) {
        StringBuilder sb = new StringBuilder();
        
        Map<String, String> regAlloc = allocateRegistersForBlock(block);
        Set<String> liveIn = block.getLiveIn();
        
        // Load live-in variables
        for (String var : liveIn) {
            if (regAlloc.containsKey(var) && !regAlloc.get(var).equals("$t8") && stackOffsets.containsKey(var)) {
                int offset = stackOffsets.get(var);
                sb.append("    lw ").append(regAlloc.get(var)).append(", -").append(offset).append("($fp)\n");
            }
        }
        
        // Generate each instruction
        for (IRInstruction instr : block.instructions) {
            sb.append(generateInstructionGreedy(instr, stackOffsets, labelMap, regAlloc));
        }
        
        // Store live-out variables
        Set<String> liveOut = block.getLiveOut();
        for (String var : liveOut) {
            if (regAlloc.containsKey(var) && !regAlloc.get(var).equals("$t8") && stackOffsets.containsKey(var)) {
                int offset = stackOffsets.get(var);
                sb.append("    sw ").append(regAlloc.get(var)).append(", -").append(offset).append("($fp)\n");
            }
        }
        
        return sb.toString();
    }
    
    private Map<String, String> allocateRegistersForBlock(MIPSBasicBlock block) {
        Map<String, Integer> useCounts = new HashMap<>();
        
        for (IRInstruction instr : block.instructions) {
            MIPSInstructionWrapper wrapper = new MIPSInstructionWrapper(instr);
            
            if (wrapper.getDefOperand() != null) {
                String var = wrapper.getDefOperand().toString();
                useCounts.put(var, useCounts.getOrDefault(var, 0) + 1);
            }
            
            for (IROperand op : instr.operands) {
                if (op instanceof IRVariableOperand) {
                    String var = op.toString();
                    useCounts.put(var, useCounts.getOrDefault(var, 0) + 1);
                }
            }
        }
        
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(useCounts.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        String[] availableRegs = {"$t0", "$t1", "$t2", "$t3", "$t4", "$t5", "$t6", "$t7"};
        Map<String, String> allocation = new HashMap<>();
        int regIdx = 0;
        
        for (Map.Entry<String, Integer> entry : sorted) {
            if (regIdx < availableRegs.length) {
                allocation.put(entry.getKey(), availableRegs[regIdx++]);
            } else {
                allocation.put(entry.getKey(), "$t8");
            }
        }
        
        return allocation;
    }
    
    private String generateInstructionGreedy(IRInstruction instr, Map<String, Integer> stackOffsets,
                                             Map<String, String> labelMap, Map<String, String> regAlloc) {
        StringBuilder sb = new StringBuilder();
        
        switch (instr.opCode) {
            case LABEL:
                String originalLabel = ((IRLabelOperand)instr.operands[0]).getName();
                sb.append(labelMap.get(originalLabel)).append(":\n");
                break;
            case ASSIGN:
                sb.append(generateAssignGreedy(instr, stackOffsets, regAlloc));
                break;
            case ADD:
            case SUB:
            case MULT:
            case DIV:
            case AND:
            case OR:
                sb.append(generateBinaryOpGreedy(instr, stackOffsets, regAlloc));
                break;
            case GOTO:
                String gotoLabel = ((IRLabelOperand)instr.operands[0]).getName();
                sb.append("    j ").append(labelMap.get(gotoLabel)).append("\n");
                break;
            case BREQ:
            case BRNEQ:
            case BRLT:
            case BRGT:
            case BRGEQ:
                sb.append(generateBranchGreedy(instr, stackOffsets, labelMap, regAlloc));
                break;
            case CALL:
            case CALLR:
                sb.append(generateCallGreedy(instr, stackOffsets, regAlloc));
                break;
            case RETURN:
                sb.append(generateReturnGreedy(instr, stackOffsets, regAlloc));
                break;
            case ARRAY_LOAD:
                sb.append(generateArrayLoadGreedy(instr, stackOffsets, regAlloc));
                break;
            case ARRAY_STORE:
                sb.append(generateArrayStoreGreedy(instr, stackOffsets, regAlloc));
                break;
        }
        
        return sb.toString();
    }
    
    private String generateAssignGreedy(IRInstruction instr, Map<String, Integer> stackOffsets, 
                                        Map<String, String> regAlloc) {
        StringBuilder sb = new StringBuilder();
        String dest = instr.operands[0].toString();
        String src = instr.operands[1].toString();
        
        String destReg = regAlloc.getOrDefault(dest, "$t9");
        
        if (isNumeric(src)) {
            sb.append("    li ").append(destReg).append(", ").append(src).append("\n");
        } else {
            String srcReg = regAlloc.getOrDefault(src, "$t8");
            if (srcReg.equals("$t8") && stackOffsets.containsKey(src)) {
                sb.append("    lw $t8, -").append(stackOffsets.get(src)).append("($fp)\n");
            }
            if (!destReg.equals(srcReg)) {
                sb.append("    move ").append(destReg).append(", ").append(srcReg).append("\n");
            }
        }
        
        if ((destReg.equals("$t8") || destReg.equals("$t9")) && stackOffsets.containsKey(dest)) {
            sb.append("    sw ").append(destReg).append(", -").append(stackOffsets.get(dest)).append("($fp)\n");
        }
        
        return sb.toString();
    }
    
    private String generateBinaryOpGreedy(IRInstruction instr, Map<String, Integer> stackOffsets,
                                          Map<String, String> regAlloc) {
        StringBuilder sb = new StringBuilder();
        String res = instr.operands[0].toString();
        String op1 = instr.operands[1].toString();
        String op2 = instr.operands[2].toString();
        
        String op1Reg = regAlloc.getOrDefault(op1, "$t8");
        if (op1Reg.equals("$t8") && stackOffsets.containsKey(op1)) {
            sb.append("    lw $t8, -").append(stackOffsets.get(op1)).append("($fp)\n");
        }
        
        String op2Reg;
        if (isNumeric(op2)) {
            sb.append("    li $t9, ").append(op2).append("\n");
            op2Reg = "$t9";
        } else {
            op2Reg = regAlloc.getOrDefault(op2, "$t9");
            if (op2Reg.equals("$t8") && stackOffsets.containsKey(op2)) {
                sb.append("    lw $t9, -").append(stackOffsets.get(op2)).append("($fp)\n");
                op2Reg = "$t9";
            } else if (op2Reg.equals(op1Reg)) {
                sb.append("    move $t9, ").append(op2Reg).append("\n");
                op2Reg = "$t9";
            }
        }
        
        String resReg = regAlloc.getOrDefault(res, "$t0");
        String mipsOp = getMIPSOp(instr.opCode);
        sb.append("    ").append(mipsOp).append(" ").append(resReg).append(", ").append(op1Reg)
          .append(", ").append(op2Reg).append("\n");
        
        if (resReg.equals("$t8") && stackOffsets.containsKey(res)) {
            sb.append("    sw $t8, -").append(stackOffsets.get(res)).append("($fp)\n");
        }
        
        return sb.toString();
    }
    
    private String generateBranchGreedy(IRInstruction instr, Map<String, Integer> stackOffsets,
                                        Map<String, String> labelMap, Map<String, String> regAlloc) {
        StringBuilder sb = new StringBuilder();
        String lbl = instr.operands[0].toString();
        String cmp1 = instr.operands[1].toString();
        String cmp2 = instr.operands[2].toString();
        
        String cmp1Reg;
        if (isNumeric(cmp1)) {
            sb.append("    li $t8, ").append(cmp1).append("\n");
            cmp1Reg = "$t8";
        } else {
            cmp1Reg = regAlloc.getOrDefault(cmp1, "$t8");
            if (cmp1Reg.equals("$t8") && stackOffsets.containsKey(cmp1)) {
                sb.append("    lw $t8, -").append(stackOffsets.get(cmp1)).append("($fp)\n");
            }
        }
        
        String cmp2Reg;
        if (isNumeric(cmp2)) {
            sb.append("    li $t9, ").append(cmp2).append("\n");
            cmp2Reg = "$t9";
        } else {
            cmp2Reg = regAlloc.getOrDefault(cmp2, "$t9");
            if (cmp2Reg.equals("$t8") && stackOffsets.containsKey(cmp2)) {
                sb.append("    lw $t9, -").append(stackOffsets.get(cmp2)).append("($fp)\n");
                cmp2Reg = "$t9";
            }
        }
        
        String branchOp = getMIPSBranch(instr.opCode);
        sb.append("    ").append(branchOp).append(" ").append(cmp1Reg).append(", ").append(cmp2Reg)
          .append(", ").append(labelMap.get(lbl)).append("\n");
        
        return sb.toString();
    }
    
    private String generateReturnGreedy(IRInstruction instr, Map<String, Integer> stackOffsets,
                                        Map<String, String> regAlloc) {
        StringBuilder sb = new StringBuilder();
        String retValue = instr.operands[0].toString();
        
        if (isNumeric(retValue)) {
            sb.append("    li $v0, ").append(retValue).append("\n");
        } else {
            String retReg = regAlloc.getOrDefault(retValue, "$t8");
            if (retReg.equals("$t8") && stackOffsets.containsKey(retValue)) {
                sb.append("    lw $v0, -").append(stackOffsets.get(retValue)).append("($fp)\n");
            } else {
                sb.append("    move $v0, ").append(retReg).append("\n");
            }
        }
        sb.append("    jr $ra\n");
        
        return sb.toString();
    }
    
    private String generateCallGreedy(IRInstruction instr, Map<String, Integer> stackOffsets,
                                      Map<String, String> regAlloc) {
        StringBuilder sb = new StringBuilder();
        boolean isCallr = instr.opCode == IRInstruction.OpCode.CALLR;
        String dest = isCallr ? instr.operands[0].toString() : null;
        String funcName = isCallr ? ((IRFunctionOperand)instr.operands[1]).getName() 
                                  : ((IRFunctionOperand)instr.operands[0]).getName();
        
        List<String> args = new ArrayList<>();
        int startIdx = isCallr ? 2 : 1;
        for (int i = startIdx; i < instr.operands.length; i++) {
            args.add(instr.operands[i].toString());
        }
        
        if (funcName.equals("geti")) {
            sb.append("    li $v0, 5\n");
            sb.append("    syscall\n");
            if (dest != null) {
                String destReg = regAlloc.getOrDefault(dest, "$t8");
                sb.append("    move ").append(destReg).append(", $v0\n");
                if (destReg.equals("$t8") && stackOffsets.containsKey(dest)) {
                    sb.append("    sw $t8, -").append(stackOffsets.get(dest)).append("($fp)\n");
                }
            }
            return sb.toString();
        }
        
        if (funcName.equals("puti")) {
            String arg = args.get(0);
            if (isNumeric(arg)) {
                sb.append("    li $a0, ").append(arg).append("\n");
            } else {
                String argReg = regAlloc.getOrDefault(arg, "$t8");
                if (argReg.equals("$t8") && stackOffsets.containsKey(arg)) {
                    sb.append("    lw $a0, -").append(stackOffsets.get(arg)).append("($fp)\n");
                } else {
                    sb.append("    move $a0, ").append(argReg).append("\n");
                }
            }
            sb.append("    li $v0, 1\n");
            sb.append("    syscall\n");
            return sb.toString();
        }
        
        if (funcName.equals("putc")) {
            String arg = args.get(0);
            if (isNumeric(arg)) {
                sb.append("    li $a0, ").append(arg).append("\n");
            } else {
                String argReg = regAlloc.getOrDefault(arg, "$t8");
                if (argReg.equals("$t8") && stackOffsets.containsKey(arg)) {
                    sb.append("    lw $a0, -").append(stackOffsets.get(arg)).append("($fp)\n");
                } else {
                    sb.append("    move $a0, ").append(argReg).append("\n");
                }
            }
            sb.append("    li $v0, 11\n");
            sb.append("    syscall\n");
            return sb.toString();
        }
        
        int stackArgOffset = 0;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (i < 4) {
                if (isNumeric(arg)) {
                    sb.append("    li $a").append(i).append(", ").append(arg).append("\n");
                } else {
                    String argReg = regAlloc.getOrDefault(arg, "$t8");
                    if (argReg.equals("$t8") && stackOffsets.containsKey(arg)) {
                        sb.append("    lw $a").append(i).append(", -").append(stackOffsets.get(arg)).append("($fp)\n");
                    } else {
                        sb.append("    move $a").append(i).append(", ").append(argReg).append("\n");
                    }
                }
            } else {
                int stackOffset = (i - 3) * 4;
                if (isNumeric(arg)) {
                    sb.append("    li $t8, ").append(arg).append("\n");
                    sb.append("    sw $t8, -").append(stackOffset).append("($sp)\n");
                } else {
                    String argReg = regAlloc.getOrDefault(arg, "$t8");
                    if (argReg.equals("$t8") && stackOffsets.containsKey(arg)) {
                        sb.append("    lw $t8, -").append(stackOffsets.get(arg)).append("($fp)\n");
                    }
                    sb.append("    sw ").append(argReg.equals("$t8") ? "$t8" : argReg).append(", -")
                      .append(stackOffset).append("($sp)\n");
                }
                stackArgOffset += 4;
            }
        }
        
        if (stackArgOffset > 0) {
            sb.append("    addi $sp, $sp, -").append(stackArgOffset).append("\n");
        }
        
        sb.append("    jal ").append(funcName).append("\n");
        
        if (dest != null) {
            String destReg = regAlloc.getOrDefault(dest, "$t8");
            sb.append("    move ").append(destReg).append(", $v0\n");
            if (destReg.equals("$t8") && stackOffsets.containsKey(dest)) {
                sb.append("    sw $t8, -").append(stackOffsets.get(dest)).append("($fp)\n");
            }
        }
        
        if (stackArgOffset > 0) {
            sb.append("    addi $sp, $sp, ").append(stackArgOffset).append("\n");
        }
        
        return sb.toString();
    }
    
    private String generateArrayLoadGreedy(IRInstruction instr, Map<String, Integer> stackOffsets,
                                           Map<String, String> regAlloc) {
        StringBuilder sb = new StringBuilder();
        String loadDest = instr.operands[0].toString();
        String loadArr = instr.operands[1].toString();
        String loadIdx = instr.operands[2].toString();
        
        if (isNumeric(loadIdx)) {
            sb.append("    li $t9, ").append(loadIdx).append("\n");
        } else {
            String idxReg = regAlloc.getOrDefault(loadIdx, "$t8");
            if (idxReg.equals("$t8") && stackOffsets.containsKey(loadIdx)) {
                sb.append("    lw $t9, -").append(stackOffsets.get(loadIdx)).append("($fp)\n");
            } else {
                sb.append("    move $t9, ").append(idxReg).append("\n");
            }
        }
        
        sb.append("    sll $t9, $t9, 2\n");
        sb.append("    lw $t8, -").append(stackOffsets.get(loadArr)).append("($fp)\n");
        sb.append("    add $t9, $t9, $t8\n");
        
        String destReg = regAlloc.getOrDefault(loadDest, "$t8");
        sb.append("    lw ").append(destReg).append(", 0($t9)\n");
        
        if (destReg.equals("$t8") && stackOffsets.containsKey(loadDest)) {
            sb.append("    sw $t8, -").append(stackOffsets.get(loadDest)).append("($fp)\n");
        }
        
        return sb.toString();
    }
    
    private String generateArrayStoreGreedy(IRInstruction instr, Map<String, Integer> stackOffsets,
                                            Map<String, String> regAlloc) {
        StringBuilder sb = new StringBuilder();
        String val = instr.operands[0].toString();
        String arr = instr.operands[1].toString();
        String index = instr.operands[2].toString();
        
        if (isNumeric(index)) {
            sb.append("    li $t9, ").append(index).append("\n");
        } else {
            String idxReg = regAlloc.getOrDefault(index, "$t8");
            if (idxReg.equals("$t8") && stackOffsets.containsKey(index)) {
                sb.append("    lw $t9, -").append(stackOffsets.get(index)).append("($fp)\n");
            } else {
                sb.append("    move $t9, ").append(idxReg).append("\n");
            }
        }
        
        String valReg;
        if (isNumeric(val)) {
            sb.append("    li $t8, ").append(val).append("\n");
            valReg = "$t8";
        } else {
            valReg = regAlloc.getOrDefault(val, "$t8");
            if (valReg.equals("$t8") && stackOffsets.containsKey(val)) {
                sb.append("    lw $t8, -").append(stackOffsets.get(val)).append("($fp)\n");
            }
        }
        
        sb.append("    lw $t0, -").append(stackOffsets.get(arr)).append("($fp)\n");
        sb.append("    sll $t9, $t9, 2\n");
        sb.append("    add $t0, $t9, $t0\n");
        sb.append("    sw ").append(valReg.equals("$t8") ? "$t8" : valReg).append(", 0($t0)\n");
        
        return sb.toString();
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
    private void debugBlocks(IRFunction func, List<MIPSBasicBlock> blockList) {
        System.err.println("\n=== DEBUG FUNCTION: " + func.name + " ===");
        System.err.println("Total instructions in IR: " + func.instructions.size());
        System.err.println("Number of blocks found: " + blockList.size());
        
        Set<String> labelsInIR = new HashSet<>();
        for (IRInstruction instr : func.instructions) {
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                labelsInIR.add(((IRLabelOperand)instr.operands[0]).getName());
            }
        }
        System.err.println("Labels in IR: " + labelsInIR);
        
        Set<String> labelsInBlocks = new HashSet<>();
        for (MIPSBasicBlock block : blockList) {
            System.err.println("Block startLine=" + block.startLine + " endLine=" + block.endLine + " instrCount=" + block.instructions.size());
            for (IRInstruction instr : block.instructions) {
                if (instr.opCode == IRInstruction.OpCode.LABEL) {
                    String label = ((IRLabelOperand)instr.operands[0]).getName();
                    labelsInBlocks.add(label);
                    System.err.println("  Contains label: " + label);
                }
            }
        }
        System.err.println("Labels in blocks: " + labelsInBlocks);
        
        Set<String> missingLabels = new HashSet<>(labelsInIR);
        missingLabels.removeAll(labelsInBlocks);
        if (!missingLabels.isEmpty()) {
            System.err.println("ERROR: MISSING LABELS IN BLOCKS: " + missingLabels);
        }
    }
}