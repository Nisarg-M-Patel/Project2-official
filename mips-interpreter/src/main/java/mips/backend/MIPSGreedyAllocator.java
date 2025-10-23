package main.java.mips.backend;

import ir.*;
import java.util.*;

public class MIPSGreedyAllocator {
    private MIPSBasicBlock block;
    private Map<String, Integer> useCounts;
    private static final String[] TEMP_REGS = {"$t0", "$t1", "$t2", "$t3", "$t4", "$t5", "$t6", "$t7"};
    private static final String SPILL_REG = "$t8";
    
    public MIPSGreedyAllocator(MIPSBasicBlock block) {
        this.block = block;
        this.useCounts = new HashMap<>();
        computeUseCounts();
    }
    
    private void computeUseCounts() {
        Map<Integer, MIPSInstructionWrapper> wrapperMap = new HashMap<>();
        
        for (IRInstruction instr : block.instructions) {
            MIPSInstructionWrapper wrapper = new MIPSInstructionWrapper(instr);
            wrapperMap.put(instr.irLineNumber, wrapper);
            
            if (wrapper.getDefOperand() != null) {
                String var = wrapper.getDefOperand().toString();
                useCounts.put(var, useCounts.getOrDefault(var, 0) + 1);
            }
        }
    }
    
    public Map<String, String> allocateRegisters() {
        Map<String, String> allocation = new HashMap<>();
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(useCounts.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        int regIdx = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            if (regIdx < TEMP_REGS.length) {
                allocation.put(entry.getKey(), TEMP_REGS[regIdx++]);
            } else {
                allocation.put(entry.getKey(), SPILL_REG);
            }
        }
        
        return allocation;
    }
}