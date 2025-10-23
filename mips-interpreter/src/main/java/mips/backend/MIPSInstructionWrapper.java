package main.java.mips.backend;

import ir.*;
import ir.operand.*;
import java.util.*;

public class MIPSInstructionWrapper {
    private IRInstruction instruction;
    private String defID;
    private Set<String> useSet;
    private boolean gen;
    private boolean mark;
    private boolean critical;
    
    public MIPSInstructionWrapper(IRInstruction instruction) {
        this.instruction = instruction;
        this.useSet = new HashSet<>();
        this.gen = false;
        this.mark = false;
        this.critical = false;
    }
    
    public IRInstruction getInstruction() {
        return instruction;
    }
    
    public void setDefID(String id) {
        this.defID = id;
    }
    
    public String getDefID() {
        return this.defID;
    }
    
    public void setGen(boolean g) {
        this.gen = g;
    }
    
    public boolean isGen() {
        return this.gen;
    }
    
    public void setMark(boolean m) {
        this.mark = m;
    }
    
    public boolean isMark() {
        return this.mark;
    }
    
    public void setCritical(boolean c) {
        this.critical = c;
    }
    
    public boolean isCritical() {
        return this.critical;
    }
    
    public void addUse(String use) {
        this.useSet.add(use);
    }
    
    public Set<String> getUseSet() {
        return new HashSet<>(this.useSet);
    }
    
    public IROperand getDefOperand() {
        if (instruction.operands == null || instruction.operands.length == 0) {
            return null;
        }
        
        IRInstruction.OpCode op = instruction.opCode;
        if (op == IRInstruction.OpCode.ASSIGN || 
            op == IRInstruction.OpCode.ADD || 
            op == IRInstruction.OpCode.SUB ||
            op == IRInstruction.OpCode.MULT || 
            op == IRInstruction.OpCode.DIV || 
            op == IRInstruction.OpCode.AND ||
            op == IRInstruction.OpCode.OR || 
            op == IRInstruction.OpCode.CALLR || 
            op == IRInstruction.OpCode.ARRAY_LOAD) {
            return instruction.operands[0];
        }
        
        return null;
    }
}