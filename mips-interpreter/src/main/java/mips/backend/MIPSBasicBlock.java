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
        buildCFG(func, defMap);
    }
    
    private void buildCFG(IRFunction func, Map<Integer, MIPSInstructionWrapper> defMap) {
        List<MIPSBasicBlock> blocks = new ArrayList<>();
        MIPSBasicBlock currentBlock = new MIPSBasicBlock();
        blocks.add(currentBlock);
        
        Map<String, MIPSBasicBlock> labelToBlock = new HashMap<>();
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
        
        blocks.clear();
        for (int i = 0; i < sortedLeaders.size(); i++) {
            MIPSBasicBlock block = new MIPSBasicBlock();
            int start = sortedLeaders.get(i);
            int end = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) : func.instructions.size();
            
            for (int j = start; j < end; j++) {
                IRInstruction instr = func.instructions.get(j);
                block.instructions.add(instr);
                
                if (instr.opCode == IRInstruction.OpCode.LABEL) {
                    String labelName = ((IRLabelOperand)instr.operands[0]).getName();
                    labelToBlock.put(labelName, block);
                }
            }
            
            if (!block.instructions.isEmpty()) {
                block.startLine = block.instructions.get(0).irLineNumber;
                block.endLine = block.instructions.get(block.instructions.size() - 1).irLineNumber;
                blocks.add(block);
            }
        }
        
        for (int i = 0; i < blocks.size(); i++) {
            MIPSBasicBlock block = blocks.get(i);
            if (block.instructions.isEmpty()) continue;
            
            IRInstruction lastInstr = block.instructions.get(block.instructions.size() - 1);
            
            if (lastInstr.opCode == IRInstruction.OpCode.GOTO ||
                lastInstr.opCode.toString().startsWith("BR")) {
                String label = ((IRLabelOperand)lastInstr.operands[0]).getName();
                MIPSBasicBlock target = labelToBlock.get(label);
                if (target != null) {
                    block.successors.add(target);
                    target.predecessors.add(block);
                }
            }
            
            if (lastInstr.opCode != IRInstruction.OpCode.GOTO && 
                lastInstr.opCode != IRInstruction.OpCode.RETURN &&
                i < blocks.size() - 1) {
                block.successors.add(blocks.get(i + 1));
                blocks.get(i + 1).predecessors.add(block);
            }
        }
        
        if (!blocks.isEmpty()) {
            this.instructions = blocks.get(0).instructions;
            this.successors = blocks.get(0).successors;
            this.predecessors = blocks.get(0).predecessors;
            this.startLine = blocks.get(0).startLine;
            this.endLine = blocks.get(0).endLine;
            this.inSet = blocks.get(0).inSet;
        }
    }
    
    public List<MIPSBasicBlock> getBlockList() {
        List<MIPSBasicBlock> result = new ArrayList<>();
        Set<MIPSBasicBlock> visited = new HashSet<>();
        collectBlocks(this, result, visited);
        return result;
    }
    
    private void collectBlocks(MIPSBasicBlock block, List<MIPSBasicBlock> result, Set<MIPSBasicBlock> visited) {
        if (visited.contains(block)) return;
        visited.add(block);
        result.add(block);
        for (MIPSBasicBlock succ : block.successors) {
            collectBlocks(succ, result, visited);
        }
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