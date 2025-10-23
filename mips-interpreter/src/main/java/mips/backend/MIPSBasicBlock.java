package main.java.mips.backend;

import ir.*;
import ir.operand.*;
import java.util.*;

public class MIPSBasicBlock {
    public List<IRInstruction> instructions;
    public List<MIPSBasicBlock> successors;
    public List<MIPSBasicBlock> predecessors;
    public int startLine;
    public int endLine;
    public Map<String, Set<Integer>> inSet;
    
    private Set<String> liveIn;
    private Set<String> liveOut;
    
    // Store all blocks created during CFG construction
    private static List<MIPSBasicBlock> allBlocks;
    private static Map<String, MIPSBasicBlock> labelToBlock;
    
    public MIPSBasicBlock() {
        this.instructions = new ArrayList<>();
        this.successors = new ArrayList<>();
        this.predecessors = new ArrayList<>();
        this.inSet = new HashMap<>();
        this.liveIn = new HashSet<>();
        this.liveOut = new HashSet<>();
    }
    
    public MIPSBasicBlock(IRFunction func, Map<Integer, MIPSInstructionWrapper> defMap) {
        this();
        allBlocks = new ArrayList<>();
        labelToBlock = new HashMap<>();
        buildCFG(func, defMap);
    }
    
    private void buildCFG(IRFunction func, Map<Integer, MIPSInstructionWrapper> defMap) {
        // Find all leaders (start of basic blocks)
        Set<Integer> leaders = new HashSet<>();
        leaders.add(0); // First instruction is always a leader
        
        for (int i = 0; i < func.instructions.size(); i++) {
            IRInstruction instr = func.instructions.get(i);
            
            // Instruction after branch/jump is a leader
            if (isBranchOrJump(instr) && i + 1 < func.instructions.size()) {
                leaders.add(i + 1);
            }
            
            // Label instruction is a leader
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                leaders.add(i);
            }
        }
        
        // Create blocks from leaders
        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        Collections.sort(sortedLeaders);
        
        for (int i = 0; i < sortedLeaders.size(); i++) {
            MIPSBasicBlock block = new MIPSBasicBlock();
            int start = sortedLeaders.get(i);
            int end = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) : func.instructions.size();
            
            // Add instructions to block
            for (int j = start; j < end; j++) {
                IRInstruction instr = func.instructions.get(j);
                block.instructions.add(instr);
                
                // Map labels to blocks
                if (instr.opCode == IRInstruction.OpCode.LABEL) {
                    String labelName = ((IRLabelOperand)instr.operands[0]).getName();
                    labelToBlock.put(labelName, block);
                }
            }
            
            if (!block.instructions.isEmpty()) {
                block.startLine = block.instructions.get(0).irLineNumber;
                block.endLine = block.instructions.get(block.instructions.size() - 1).irLineNumber;
                allBlocks.add(block);
            }
        }
        
        // Link blocks (add successors/predecessors)
        for (int i = 0; i < allBlocks.size(); i++) {
            MIPSBasicBlock block = allBlocks.get(i);
            if (block.instructions.isEmpty()) continue;
            
            IRInstruction lastInstr = block.instructions.get(block.instructions.size() - 1);
            
            // Handle branches/gotos
            if (lastInstr.opCode == IRInstruction.OpCode.GOTO ||
                lastInstr.opCode == IRInstruction.OpCode.BREQ ||
                lastInstr.opCode == IRInstruction.OpCode.BRNEQ ||
                lastInstr.opCode == IRInstruction.OpCode.BRLT ||
                lastInstr.opCode == IRInstruction.OpCode.BRGT ||
                lastInstr.opCode == IRInstruction.OpCode.BRGEQ) {
                
                String label = ((IRLabelOperand)lastInstr.operands[0]).getName();
                MIPSBasicBlock target = labelToBlock.get(label);
                if (target != null) {
                    block.successors.add(target);
                    target.predecessors.add(block);
                }
            }
            
            // Fall-through to next block (unless it's a goto or return)
            if (lastInstr.opCode != IRInstruction.OpCode.GOTO && 
                lastInstr.opCode != IRInstruction.OpCode.RETURN &&
                i < allBlocks.size() - 1) {
                MIPSBasicBlock nextBlock = allBlocks.get(i + 1);
                block.successors.add(nextBlock);
                nextBlock.predecessors.add(block);
            }
        }
        
        // Copy first block's data to this instance (head)
        if (!allBlocks.isEmpty()) {
            MIPSBasicBlock firstBlock = allBlocks.get(0);
            this.instructions = firstBlock.instructions;
            this.successors = firstBlock.successors;
            this.predecessors = firstBlock.predecessors;
            this.startLine = firstBlock.startLine;
            this.endLine = firstBlock.endLine;
            this.inSet = firstBlock.inSet;
        }
    }
    
    public List<MIPSBasicBlock> getBlockList() {
        return new ArrayList<>(allBlocks);
    }
    
    public Set<String> computeLiveOut() {
        liveOut.clear();
        for (MIPSBasicBlock succ : successors) {
            liveOut.addAll(succ.getLiveIn());
        }
        return new HashSet<>(liveOut);
    }
    
    public Set<String> computeLiveIn() {
        liveIn.clear();
        Set<String> currentLive = new HashSet<>(liveOut);
        
        for (int i = instructions.size() - 1; i >= 0; i--) {
            IRInstruction instr = instructions.get(i);
            MIPSInstructionWrapper wrapper = new MIPSInstructionWrapper(instr);
            
            if (wrapper.getDefOperand() != null) {
                currentLive.remove(wrapper.getDefOperand().toString());
            }
            
            for (String use : wrapper.getUseSet()) {
                int parenIdx = use.indexOf('(');
                if (parenIdx > 0) {
                    String varName = use.substring(parenIdx + 1, use.length() - 1);
                    currentLive.add(varName);
                }
            }
        }
        
        liveIn = currentLive;
        return new HashSet<>(liveIn);
    }
    
    public Set<String> getLiveIn() {
        return new HashSet<>(liveIn);
    }
    
    public Set<String> getLiveOut() {
        return new HashSet<>(liveOut);
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
    
    public static <T> Set<T> cloneSet(Set<T> set) {
        return new HashSet<>(set);
    }
}