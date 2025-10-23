package main.java.mips.backend;

import ir.*;
import ir.operand.*;
import java.util.*;

public class MIPSReachingDefinitions {
    
    public static Map<Integer, MIPSInstructionWrapper> computeDefReachMap(IRFunction func) {
        Map<Integer, MIPSInstructionWrapper> genInstructions = new HashMap<>();
        
        for (IRVariableOperand param : func.parameters) {
            Integer funcLine = func.instructions.get(0).irLineNumber - 3;
            String defID = "def" + funcLine;
            
            IRInstruction fakeInstr = new IRInstruction(IRInstruction.OpCode.ASSIGN, 
                                                        new IROperand[]{param}, 
                                                        funcLine);
            MIPSInstructionWrapper wrapper = new MIPSInstructionWrapper(fakeInstr);
            wrapper.setDefID(defID);
            wrapper.setGen(true);
            
            genInstructions.put(funcLine, wrapper);
        }
        
        for (IRInstruction instr : func.instructions) {
            if (isDefinitionOp(instr.opCode)) {
                MIPSInstructionWrapper wrapper = new MIPSInstructionWrapper(instr);
                String defID = "def" + instr.irLineNumber;
                wrapper.setDefID(defID);
                wrapper.setGen(true);
                
                genInstructions.put(instr.irLineNumber, wrapper);
            }
            
            if (isCriticalOp(instr)) {
                MIPSInstructionWrapper wrapper = genInstructions.computeIfAbsent(
                    instr.irLineNumber, k -> new MIPSInstructionWrapper(instr));
                wrapper.setCritical(true);
                wrapper.setMark(true);
            }
        }
        
        return genInstructions;
    }
    
    public static void computeUseSet(List<MIPSBasicBlock> blockList, 
                                    Map<Integer, MIPSInstructionWrapper> defMap) {
        for (MIPSBasicBlock block : blockList) {
            for (int i = 0; i < block.instructions.size(); i++) {
                IRInstruction instr = block.instructions.get(i);
                MIPSInstructionWrapper wrapper = defMap.computeIfAbsent(
                    instr.irLineNumber, k -> new MIPSInstructionWrapper(instr));
                
                IROperand defOperand = wrapper.getDefOperand();
                String definedVar = (defOperand != null) ? defOperand.toString() : "";
                
                for (int opIdx = 0; opIdx < instr.operands.length; opIdx++) {
                    String operandName = instr.operands[opIdx].toString();
                    
                    if (opIdx == 0 && instr.opCode != IRInstruction.OpCode.RETURN 
                                   && instr.opCode != IRInstruction.OpCode.ARRAY_STORE) {
                        continue;
                    } else if(opIdx == 1 && instr.opCode == IRInstruction.OpCode.ARRAY_STORE) {
                        continue;
                    }
                    
                    if (block.inSet.containsKey(operandName)) {
                        ArrayList<Integer> reachingDefs = new ArrayList<>(block.inSet.get(operandName));
                        Integer closestDefLine = null;
                        
                        for (int j = 0; j < i; j++) {
                            IRInstruction prevInstr = block.instructions.get(j);
                            MIPSInstructionWrapper prevWrapper = defMap.get(prevInstr.irLineNumber);
                            if (prevWrapper != null && prevWrapper.getDefOperand() != null &&
                                prevWrapper.getDefOperand().toString().equals(operandName)) {
                                closestDefLine = prevInstr.irLineNumber;
                            }
                        }
                        
                        if (closestDefLine != null) {
                            MIPSInstructionWrapper defWrapper = defMap.get(closestDefLine);
                            if (defWrapper != null && defWrapper.getDefOperand().toString().equals(operandName)) {
                                wrapper.addUse(defWrapper.getDefID() + '(' + defWrapper.getDefOperand() + ')');
                            }
                        } else {
                            for (Integer defLine : reachingDefs) {
                                MIPSInstructionWrapper defWrapper = defMap.get(defLine);
                                if (defWrapper != null && defWrapper.getDefOperand() != null &&
                                    defWrapper.getDefOperand().toString().equals(operandName)) {
                                    wrapper.addUse(defWrapper.getDefID() + '(' + defWrapper.getDefOperand() + ')');
                                }
                            }
                        }
                    } else {
                        Integer closestDefLine = null;
                        for (int j = 0; j < i; j++) {
                            IRInstruction prevInstr = block.instructions.get(j);
                            MIPSInstructionWrapper prevWrapper = defMap.get(prevInstr.irLineNumber);
                            if (prevWrapper != null && prevWrapper.getDefOperand() != null &&
                                prevWrapper.getDefOperand().toString().equals(operandName)) {
                                closestDefLine = prevInstr.irLineNumber;
                            }
                        }
                        
                        if (closestDefLine != null) {
                            MIPSInstructionWrapper defWrapper = defMap.get(closestDefLine);
                            if (defWrapper != null && defWrapper.getDefOperand().toString().equals(operandName)) {
                                wrapper.addUse(defWrapper.getDefID() + '(' + defWrapper.getDefOperand() + ')');
                            }
                        }
                    }
                }
            }
        }
    }
    
    private static boolean isDefinitionOp(IRInstruction.OpCode opCode) {
        return opCode == IRInstruction.OpCode.ADD || 
               opCode == IRInstruction.OpCode.SUB ||
               opCode == IRInstruction.OpCode.MULT || 
               opCode == IRInstruction.OpCode.DIV ||
               opCode == IRInstruction.OpCode.AND || 
               opCode == IRInstruction.OpCode.OR ||
               opCode == IRInstruction.OpCode.ASSIGN || 
               opCode == IRInstruction.OpCode.CALLR ||
               opCode == IRInstruction.OpCode.ARRAY_LOAD;
    }
    
    private static boolean isCriticalOp(IRInstruction instr) {
        IRInstruction.OpCode opCode = instr.opCode;
        if (opCode == IRInstruction.OpCode.ASSIGN && instr.operands.length == 3) {
            return true;
        }
        return opCode == IRInstruction.OpCode.GOTO ||
               opCode == IRInstruction.OpCode.BREQ || 
               opCode == IRInstruction.OpCode.BRNEQ ||
               opCode == IRInstruction.OpCode.BRLT || 
               opCode == IRInstruction.OpCode.BRGT ||
               opCode == IRInstruction.OpCode.BRGEQ || 
               opCode == IRInstruction.OpCode.RETURN ||
               opCode == IRInstruction.OpCode.CALL || 
               opCode == IRInstruction.OpCode.CALLR ||
               opCode == IRInstruction.OpCode.LABEL || 
               opCode == IRInstruction.OpCode.ARRAY_STORE;
    }
}