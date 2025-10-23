package main.java.mips.backend;

import ir.*;
import ir.datatype.IRArrayType;
import ir.operand.*;
import java.io.PrintStream;
import java.util.*;

public class MIPSGreedyAllocator {
    private PrintStream output;

    // Registers available for allocation ($t0 - $t7)
    private static final String[] ALLOCATABLE_REGS = {
            "$t0", "$t1", "$t2", "$t3", "$t4", "$t5", "$t6", "$t7"
    };
    // Registers reserved for loading/storing spills and intermediate values
    private static final String TEMP_REG_1 = "$t8"; // Use for op1, results, spilled values
    private static final String TEMP_REG_2 = "$t9"; // Use for op2, addresses

    // --- NEW: Map to store allocation for the current block ---
    private Map<String, Integer> operandToRegisterMap = null;
    private Map<String, Integer> stackOffsets = null; // Store globally for the function

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
                // Epilogue for non-main functions is now handled within generateFunction
                output.println("    jr $ra");
                output.println();
            }
        }
    }

    private void generateFunction(IRFunction func) {
        // --- Reset state for the new function ---
        this.stackOffsets = new HashMap<>();
        this.operandToRegisterMap = null; // Will be set per block
        // ---

        Map<String, String> labelMap = new HashMap<>();
        String funcPrefix = func.name + "_";
        int fp_offset = 4;

        Set<String> paramNames = new HashSet<>();
        int local_size = 0;
        int arg_size = 0;

        // Build label mapping
        for (IRInstruction instr : func.instructions) {
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                String originalLabel = ((IRLabelOperand) instr.operands[0]).getName();
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

        local_size += arg_size; // Total stack space needed for params + local vars

        // --- Basic Block Identification and Allocation ---
        List<MIPSBasicBlock> basicBlocks = buildBasicBlocks(func, labelMap);
        Map<MIPSBasicBlock, Map<String, Integer>> blockRegisterMaps = new HashMap<>();

        for (MIPSBasicBlock block : basicBlocks) {
            Map<String, Integer> usageCounts = countUsesInBlock(block);
            Map<String, Integer> regMap = allocateRegistersGreedy(usageCounts);
            blockRegisterMaps.put(block, regMap);
            // Add any variables used/defined in the block to stackOffsets if not already there
            // (This handles temp variables implicitly defined by IR but not in func.variables)
            addAllVarsToStackMap(block, stackOffsets, fp_offset);
            fp_offset = stackOffsets.size() * 4 + 4; // Update fp_offset based on current size
        }
        // Recalculate final local_size based on potentially added temp variables
        local_size = (stackOffsets.size() - func.parameters.size()) * 4 + arg_size;


        // --- Function prologue ---
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
                 // Load from caller's stack frame relative to current $fp
                 // The address is fp + (argOffset - 4) since fp points one word below the first arg passed on stack
                output.println("    lw $t0, " + (argOffset + 4) + "($fp)");
                argOffset -= 4;
                output.println("    sw $t0, -" + offset + "($fp)");
            }
        }

        // Allocate stack space (local_size + space for $ra)
        output.println("    addi $sp, $sp, -" + local_size);
        output.println("    addi $sp, $sp, -4"); // Space for $ra
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

        // --- Generate Code Block by Block ---
        for (MIPSBasicBlock block : basicBlocks) {
            this.operandToRegisterMap = blockRegisterMaps.get(block); // Set current block's map

            // Generate Block Entry Loads (Load allocated registers)
            output.println("    # Block Entry: Load registers for Block starting line " + block.startLine);
            for (Map.Entry<String, Integer> entry : operandToRegisterMap.entrySet()) {
                String varName = entry.getKey();
                int regNum = entry.getValue();
                if (regNum >= 0 && regNum < ALLOCATABLE_REGS.length) { // Check if allocated (not spilled)
                    String physReg = ALLOCATABLE_REGS[regNum];
                    Integer offset = stackOffsets.get(varName);
                    if (offset != null) {
                        output.println("    lw " + physReg + ", -" + offset + "($fp)  # Load " + varName + " into " + physReg);
                    } else {
                         output.println("    # WARNING: No stack offset found for allocated register " + varName + " in block entry");
                    }
                }
            }

            // --- START OF FIX ---

            // Check *only* the LAST instruction for an unconditional jump
            boolean endedWithUnconditionalJump = false;
            if (!block.instructions.isEmpty()) {
                IRInstruction lastInstr = block.instructions.get(block.instructions.size() - 1);
                if (lastInstr.opCode == IRInstruction.OpCode.GOTO || lastInstr.opCode == IRInstruction.OpCode.RETURN) {
                    endedWithUnconditionalJump = true;
                }
            }

            // Generate instructions for the block
            for (IRInstruction instr : block.instructions) {
                printInstruction(instr, func, labelMap); // Use the new printInstruction
            }

            // Generate Block Exit Stores (Store allocated registers that might be live out)
            // Only skip if the block ended with an unconditional GOTO or RETURN
             if (!endedWithUnconditionalJump) {
                 output.println("    # Block Exit: Store registers for Block ending line " + block.endLine);
                 for (Map.Entry<String, Integer> entry : operandToRegisterMap.entrySet()) {
                     String varName = entry.getKey();
                     int regNum = entry.getValue();
                     if (regNum >= 0 && regNum < ALLOCATABLE_REGS.length) { // Check if allocated (not spilled)
                         String physReg = ALLOCATABLE_REGS[regNum];
                         Integer offset = stackOffsets.get(varName);
                         if (offset != null) {
                             output.println("    sw " + physReg + ", -" + offset + "($fp)  # Store " + varName + " from " + physReg);
                         } else {
                              output.println("    # WARNING: No stack offset found for allocated register " + varName + " in block exit");
                         }
                     }
                 }
             } else {
                 output.println("    # Block Exit: Skipped stores due to unconditional jump/return at line " + block.endLine);
             }
             // --- END OF FIX ---

            output.println();
        }

        // --- Function epilogue ---
        // Ensure there's a label for potential jumps to the end (needed if last block doesn't fall through)
        output.println(funcPrefix + "epilogue:");
        output.println("    lw   $ra, 0($sp)");
        output.println("    addi $sp, $sp, 4"); // Pop $ra
        output.println("    addi $sp, $sp, " + local_size); // Pop local vars
        output.println("    lw   $fp, 0($sp)");
        output.println("    addi $sp, $sp, 4"); // Pop old $fp

        // The 'jr $ra' is now handled outside for non-main functions
    }


     // --- NEW: Helper to ensure all variables touched in a block have a stack slot ---
     private void addAllVarsToStackMap(MIPSBasicBlock block, Map<String, Integer> stackMap, int currentFpOffset) {
         Set<String> blockVars = new HashSet<>();
         for (IRInstruction instr : block.instructions) {
             for (IROperand op : instr.operands) {
                 if (op instanceof IRVariableOperand) {
                     blockVars.add(op.toString());
                 }
             }
         }

         int nextOffset = currentFpOffset;
          // Ensure every variable mentioned in the block gets a stack offset if it doesn't have one
         for (String varName : blockVars) {
             if (!stackMap.containsKey(varName)) {
                 stackMap.put(varName, nextOffset);
                 nextOffset += 4;
             }
         }
     }


    // --- NEW: Replaces generateInstructionInBlock and its helpers ---
    // This function closely follows the logic of GreedyAllocator.java::printInstruction
    private void printInstruction(IRInstruction instruction, IRFunction func, Map<String, String> labelMap) {
        String funcPrefix = func.name + "_"; // Use function name prefix for labels

        if (instruction.opCode == IRInstruction.OpCode.LABEL) {
            String labelName = instruction.operands[0].toString(); // Use toString() which gets the name
            String uniqueLabel = funcPrefix + labelName;
            output.println(uniqueLabel + ":");
            return;
        }

        String opString = instruction.opCode.toString().toLowerCase(); // Use lowercase opcode string
        IROperand[] operands = instruction.operands;

        switch (instruction.opCode) {
            case ASSIGN:
                String destVar = operands[0].toString();
                String srcVal = operands[1].toString();
                String destReg = getRegister(destVar); // Returns e.g., "$t0" or "" if spilled
                String srcReg = getRegister(srcVal);

                if (isNumeric(srcVal)) {
                    if (destReg.isEmpty()) { // Spill destination
                        output.println("    li " + TEMP_REG_1 + ", " + srcVal);
                        output.println("    sw " + TEMP_REG_1 + ", -" + getOff(destVar) + "($fp)");
                    } else { // Destination in register
                        output.println("    li " + destReg + ", " + srcVal);
                    }
                } else { // Source is a variable
                    if (destReg.isEmpty() && srcReg.isEmpty()) { // Spill source and destination
                        output.println("    lw " + TEMP_REG_1 + ", -" + getOff(srcVal) + "($fp)");
                        output.println("    sw " + TEMP_REG_1 + ", -" + getOff(destVar) + "($fp)");
                    } else if (destReg.isEmpty()) { // Spill dest only
                        output.println("    sw " + srcReg + ", -" + getOff(destVar) + "($fp)");
                    } else if (srcReg.isEmpty()) { // Spill source only
                        output.println("    lw " + destReg + ", -" + getOff(srcVal) + "($fp)");
                    } else { // Both in registers
                         if (!destReg.equals(srcReg)) { // Avoid move to self
                             output.println("    move " + destReg + ", " + srcReg);
                         }
                    }
                }
                break;

            case ADD:
            case SUB:
            case MULT:
            case DIV:
            case AND:
            case OR:
                String resVar = operands[0].toString();
                String op1Var = operands[1].toString();
                String op2Var = operands[2].toString();
                String resReg = getRegister(resVar);
                String op1Reg = getRegister(op1Var);
                String op2Reg = getRegister(op2Var);

                String mipsOp = getMIPSOp(instruction.opCode); // Gets "add", "sub", etc.

                // --- Load operands if necessary ---
                String op1LoadReg = TEMP_REG_1; // Use $t8 for op1 if spilled/immediate
                String op2LoadReg = TEMP_REG_2; // Use $t9 for op2 if spilled/immediate
                boolean op1InMemory = false;
                boolean op2InMemory = false;

                if (isNumeric(op1Var)) {
                    output.println("    li " + op1LoadReg + ", " + op1Var);
                    op1Reg = op1LoadReg;
                } else if (op1Reg.isEmpty()) {
                    output.println("    lw " + op1LoadReg + ", -" + getOff(op1Var) + "($fp)");
                    op1Reg = op1LoadReg;
                    op1InMemory = true;
                }

                // Handle immediate operations (like addi)
                if (!op1InMemory && (instruction.opCode == IRInstruction.OpCode.ADD || instruction.opCode == IRInstruction.OpCode.SUB) && isNumeric(op2Var)) {
                    String immOp = (instruction.opCode == IRInstruction.OpCode.ADD) ? "addi" : "subi";
                    String targetRegImm;
                    if (resReg.isEmpty()) {
                        targetRegImm = TEMP_REG_1; // Calculate into temp
                        output.println("    " + immOp + " " + targetRegImm + ", " + op1Reg + ", " + op2Var);
                        output.println("    sw " + targetRegImm + ", -" + getOff(resVar) + "($fp)"); // Store spilled result
                    } else {
                        targetRegImm = resReg; // Calculate into dest reg
                        output.println("    " + immOp + " " + targetRegImm + ", " + op1Reg + ", " + op2Var);
                    }
                    break; // Done with this instruction
                }


                // --- Non-immediate or non-addi/subi op2 ---
                if (isNumeric(op2Var)) {
                    output.println("    li " + op2LoadReg + ", " + op2Var);
                    op2Reg = op2LoadReg;
                } else if (op2Reg.isEmpty()) {
                     // If op1 also spilled, use $t9, otherwise reuse $t8
                     String loadTarget = op1InMemory ? TEMP_REG_2 : TEMP_REG_1;
                     output.println("    lw " + loadTarget + ", -" + getOff(op2Var) + "($fp)");
                     op2Reg = loadTarget;
                     op2InMemory = true;
                }

                 // --- Perform operation ---
                 String targetReg;
                 if (resReg.isEmpty()) {
                      // If result is spilled, calculate into TEMP_REG_1 ($t8)
                     targetReg = TEMP_REG_1;
                     output.println("    " + mipsOp + " " + targetReg + ", " + op1Reg + ", " + op2Reg);
                      // Store spilled result immediately
                     output.println("    sw " + targetReg + ", -" + getOff(resVar) + "($fp)");
                 } else {
                     // If result is in register, calculate directly into it
                     targetReg = resReg;
                     output.println("    " + mipsOp + " " + targetReg + ", " + op1Reg + ", " + op2Reg);
                 }
                break;

            case GOTO:
                output.println("    j " + funcPrefix + operands[0].toString());
                break;

            case BREQ:
            case BRNEQ:
            case BRLT:
            case BRGT:
            case BRGEQ:
                // Note: BRLEQ is not a standard IR op, handle if needed
                String lbl = operands[0].toString();
                String cmp1Var = operands[1].toString();
                String cmp2Var = operands[2].toString();
                String cmp1Reg = getRegister(cmp1Var);
                String cmp2Reg = getRegister(cmp2Var);

                String cmp1LoadReg = TEMP_REG_1; // Use $t8
                String cmp2LoadReg = TEMP_REG_2; // Use $t9

                if (isNumeric(cmp1Var)) {
                    output.println("    li " + cmp1LoadReg + ", " + cmp1Var);
                    cmp1Reg = cmp1LoadReg;
                } else if (cmp1Reg.isEmpty()) {
                    output.println("    lw " + cmp1LoadReg + ", -" + getOff(cmp1Var) + "($fp)");
                    cmp1Reg = cmp1LoadReg;
                }

                if (isNumeric(cmp2Var)) {
                     // If cmp1 used $t8, use $t9 for cmp2 immediate, else reuse $t8
                    String loadTarget = (cmp1Reg.equals(TEMP_REG_1)) ? TEMP_REG_2 : TEMP_REG_1;
                    output.println("    li " + loadTarget + ", " + cmp2Var);
                    cmp2Reg = loadTarget;
                } else if (cmp2Reg.isEmpty()) {
                     // If cmp1 used $t8, use $t9 for cmp2 load, else reuse $t8
                     String loadTarget = (cmp1Reg.equals(TEMP_REG_1)) ? TEMP_REG_2 : TEMP_REG_1;
                     output.println("    lw " + loadTarget + ", -" + getOff(cmp2Var) + "($fp)");
                     cmp2Reg = loadTarget;
                }

                String branchOp = getMIPSBranchOp(instruction.opCode.toString()); // Pass string opcode
                output.println("    " + branchOp + " " + cmp1Reg + ", " + cmp2Reg + ", " + funcPrefix + lbl);
                break;

            case RETURN:
                 String retValue = operands[0].toString();
                 if (isNumeric(retValue)) {
                     output.println("    li $v0, " + retValue);
                 } else {
                     String retReg = getRegister(retValue);
                     if (retReg.isEmpty()) {
                         output.println("    lw $v0, -" + getOff(retValue) + "($fp)");
                     } else {
                         output.println("    move $v0, " + retReg);
                     }
                 }
                 // Jump to function epilogue to handle stack cleanup and actual return
                 output.println("    j " + funcPrefix + "epilogue");
                break;

            case CALL:
            case CALLR:
                boolean isCallr = instruction.opCode == IRInstruction.OpCode.CALLR;
                String destCallrVar = isCallr ? operands[0].toString() : null;
                String funcName = isCallr ? operands[1].toString() : operands[0].toString();

                List<String> argVars = new ArrayList<>();
                int argStartIdx = isCallr ? 2 : 1;
                for (int i = argStartIdx; i < operands.length; i++) {
                    argVars.add(operands[i].toString());
                }

                // Handle intrinsics first (no caller save needed)
                 if (handleIntrinsic(funcName, argVars, destCallrVar)) {
                     break; // Intrinsic handled, skip general call logic
                 }


                 // --- General Function Call ---
                 // 1. Caller-Save (Save ALL registers allocated in this block)
                 output.println("    # Caller-save registers for call to " + funcName);
                 for (Map.Entry<String, Integer> entry : operandToRegisterMap.entrySet()) {
                     int regNum = entry.getValue();
                     if (regNum >= 0 && regNum < ALLOCATABLE_REGS.length) {
                         String varName = entry.getKey();
                         String pReg = ALLOCATABLE_REGS[regNum];
                         Integer offset = stackOffsets.get(varName);
                         if (offset != null) {
                             output.println("    sw " + pReg + ", -" + offset + "($fp)");
                         }
                     }
                 }

                 // 2. Pass Arguments ($a0-$a3 and stack)
                 int stackArgBytes = 0;
                 for (int i = 0; i < argVars.size(); i++) {
                     String argVar = argVars.get(i);
                     String argReg = getRegister(argVar);
                     String sourceReg; // Register holding the value to pass

                     if (isNumeric(argVar)) {
                         output.println("    li " + TEMP_REG_1 + ", " + argVar);
                         sourceReg = TEMP_REG_1;
                     } else if (argReg.isEmpty()) {
                         output.println("    lw " + TEMP_REG_1 + ", -" + getOff(argVar) + "($fp)");
                         sourceReg = TEMP_REG_1;
                     } else {
                         sourceReg = argReg; // Argument already in its allocated register
                     }

                     if (i < 4) { // Pass in $a0-$a3
                         output.println("    move $a" + i + ", " + sourceReg);
                     } else { // Pass on stack (relative to $sp)
                         // Calculate offset relative to $sp *before* potential adjustment
                         int stackOffset = (i - 4) * 4;
                         // Ensure space is allocated before storing
                         if (i == 4) { // Only adjust $sp once for all stack args
                             int totalStackArgs = argVars.size() - 4;
                             stackArgBytes = totalStackArgs * 4;
                             if (stackArgBytes > 0) {
                                  output.println("    addi $sp, $sp, -" + stackArgBytes + " # Make space for stack args");
                             }
                         }
                         if (stackArgBytes > 0) {
                             output.println("    sw " + sourceReg + ", " + stackOffset + "($sp) # Store stack arg " + i);
                         }
                     }
                 }

                 // 3. Call
                 output.println("    jal " + funcName);

                 // 4. Clean up stack args space (if any)
                 if (stackArgBytes > 0) {
                     output.println("    addi $sp, $sp, " + stackArgBytes + " # Clean up stack args");
                 }

                 // 5. Handle return value for CALLR
                 if (isCallr && destCallrVar != null) {
                     String destRegCallr = getRegister(destCallrVar);
                     if (destRegCallr.isEmpty()) { // Spilled destination
                         output.println("    sw $v0, -" + getOff(destCallrVar) + "($fp)");
                     } else { // Destination in register
                         output.println("    move " + destRegCallr + ", $v0");
                     }
                 }

                 // 6. Caller-Restore (Restore ALL registers saved before)
                 output.println("    # Caller-restore registers after call to " + funcName);
                 // Iterate in reverse order of allocation might be slightly better cache-wise, but not critical
                 for (Map.Entry<String, Integer> entry : operandToRegisterMap.entrySet()) {
                     int regNum = entry.getValue();
                     if (regNum >= 0 && regNum < ALLOCATABLE_REGS.length) {
                         String varName = entry.getKey();
                         String pReg = ALLOCATABLE_REGS[regNum];
                         Integer offset = stackOffsets.get(varName);

                         // *** The crucial fix from previous attempts: Check if the restored register was the destination ***
                         if (isCallr && varName.equals(destCallrVar)) {
                              output.println("    # Skip restore for destination " + varName + " in " + pReg);
                              continue; // Don't restore if it was the destination
                         }

                         if (offset != null) {
                             output.println("    lw " + pReg + ", -" + offset + "($fp)");
                         }
                     }
                 }
                break;

            case ARRAY_STORE:
                // array_store, value, arr, index -> sw value, offset(arr_base)
                String valVar = operands[0].toString();
                String arrVar = operands[1].toString();
                String indexVar = operands[2].toString();
                String valReg = getRegister(valVar);
                String arrReg = getRegister(arrVar);
                String indexReg = getRegister(indexVar);

                // Load value into TEMP_REG_1 ($t8) if needed
                if (isNumeric(valVar)) {
                    output.println("    li " + TEMP_REG_1 + ", " + valVar);
                    valReg = TEMP_REG_1;
                } else if (valReg.isEmpty()) {
                    output.println("    lw " + TEMP_REG_1 + ", -" + getOff(valVar) + "($fp)");
                    valReg = TEMP_REG_1;
                }

                // Load array base address into TEMP_REG_2 ($t9) if needed
                 String baseAddrReg;
                 if (arrReg.isEmpty()) {
                     output.println("    lw " + TEMP_REG_2 + ", -" + getOff(arrVar) + "($fp)");
                     baseAddrReg = TEMP_REG_2;
                 } else {
                     baseAddrReg = arrReg;
                 }

                // Load index into TEMP_REG_1 ($t8), calculate offset * 4, store in TEMP_REG_1
                 if (isNumeric(indexVar)) {
                     output.println("    li " + TEMP_REG_1 + ", " + indexVar);
                     output.println("    sll " + TEMP_REG_1 + ", " + TEMP_REG_1 + ", 2"); // offset in bytes
                 } else if (indexReg.isEmpty()) {
                     output.println("    lw " + TEMP_REG_1 + ", -" + getOff(indexVar) + "($fp)");
                     output.println("    sll " + TEMP_REG_1 + ", " + TEMP_REG_1 + ", 2"); // offset in bytes
                 } else { // index is in a register
                     output.println("    sll " + TEMP_REG_1 + ", " + indexReg + ", 2"); // offset in bytes
                 }

                 // Add base address and offset, store final address in TEMP_REG_2 ($t9)
                 output.println("    add " + TEMP_REG_2 + ", " + baseAddrReg + ", " + TEMP_REG_1 + " # Calculate final address");

                 // Store the value (already in valReg)
                 output.println("    sw " + valReg + ", 0(" + TEMP_REG_2 + ")");
                break;

            case ARRAY_LOAD:
                 // array_load, dest, arr, index -> lw dest, offset(arr_base)
                 String loadDestVar = operands[0].toString();
                 String loadArrVar = operands[1].toString();
                 String loadIndexVar = operands[2].toString();
                 String loadDestReg = getRegister(loadDestVar);
                 String loadArrReg = getRegister(loadArrVar);
                 String loadIndexReg = getRegister(loadIndexVar);

                // Load array base address into TEMP_REG_2 ($t9) if needed
                 String loadBaseAddrReg;
                 if (loadArrReg.isEmpty()) {
                     output.println("    lw " + TEMP_REG_2 + ", -" + getOff(loadArrVar) + "($fp)");
                     loadBaseAddrReg = TEMP_REG_2;
                 } else {
                     loadBaseAddrReg = loadArrReg;
                 }

                 // Load index into TEMP_REG_1 ($t8), calculate offset * 4, store in TEMP_REG_1
                 if (isNumeric(loadIndexVar)) {
                     output.println("    li " + TEMP_REG_1 + ", " + loadIndexVar);
                     output.println("    sll " + TEMP_REG_1 + ", " + TEMP_REG_1 + ", 2"); // offset in bytes
                 } else if (loadIndexReg.isEmpty()) {
                     output.println("    lw " + TEMP_REG_1 + ", -" + getOff(loadIndexVar) + "($fp)");
                     output.println("    sll " + TEMP_REG_1 + ", " + TEMP_REG_1 + ", 2"); // offset in bytes
                 } else { // index is in a register
                     output.println("    sll " + TEMP_REG_1 + ", " + loadIndexReg + ", 2"); // offset in bytes
                 }

                 // Add base address and offset, store final address in TEMP_REG_2 ($t9)
                 output.println("    add " + TEMP_REG_2 + ", " + loadBaseAddrReg + ", " + TEMP_REG_1 + " # Calculate final address");

                 // Load the value from memory into destination register or temp register
                 if (loadDestReg.isEmpty()) { // Spilled destination
                      output.println("    lw " + TEMP_REG_1 + ", 0(" + TEMP_REG_2 + ")");
                      // Store immediately to stack
                      output.println("    sw " + TEMP_REG_1 + ", -" + getOff(loadDestVar) + "($fp)");
                 } else { // Destination is in register
                      output.println("    lw " + loadDestReg + ", 0(" + TEMP_REG_2 + ")");
                 }
                break;

            default:
                output.println("    # UNSUPPORTED IR OpCode: " + opString);
        }
    }


     // --- NEW: Handle Intrinsics (returns true if handled, false otherwise) ---
     private boolean handleIntrinsic(String funcName, List<String> args, String destVar) {
         switch (funcName) {
             case "geti":
                 output.println("    li $v0, 5");
                 output.println("    syscall");
                 if (destVar != null) {
                     String destReg = getRegister(destVar);
                     if (destReg.isEmpty()) {
                         output.println("    sw $v0, -" + getOff(destVar) + "($fp)");
                     } else {
                         output.println("    move " + destReg + ", $v0");
                     }
                 }
                 return true;
             case "getc":
                 output.println("    li $v0, 12");
                 output.println("    syscall");
                 if (destVar != null) {
                     String destReg = getRegister(destVar);
                     if (destReg.isEmpty()) {
                         output.println("    sw $v0, -" + getOff(destVar) + "($fp)");
                     } else {
                         output.println("    move " + destReg + ", $v0");
                     }
                 }
                 return true;
             case "puti":
                 String argVar = args.get(0);
                 String argReg = getRegister(argVar);
                 if (isNumeric(argVar)) {
                     output.println("    li $a0, " + argVar);
                 } else if (argReg.isEmpty()) {
                     output.println("    lw $a0, -" + getOff(argVar) + "($fp)");
                 } else {
                     output.println("    move $a0, " + argReg);
                 }
                 output.println("    li $v0, 1");
                 output.println("    syscall");
                 return true;
             case "putc":
                 String charArgVar = args.get(0);
                 String charArgReg = getRegister(charArgVar);
                 if (isNumeric(charArgVar)) {
                     output.println("    li $a0, " + charArgVar);
                 } else if (charArgReg.isEmpty()) {
                     output.println("    lw $a0, -" + getOff(charArgVar) + "($fp)");
                 } else {
                     output.println("    move $a0, " + charArgReg);
                 }
                 output.println("    li $v0, 11");
                 output.println("    syscall");
                 return true;
             default:
                 return false; // Not an intrinsic
         }
     }

    // --- NEW: Helper to get register string or "" if spilled ---
    // Mimics logic from GreedyAllocator.java::getRegister
    private String getRegister(String operand) {
        if (operandToRegisterMap == null) return ""; // Should not happen if called correctly
        Integer regNum = operandToRegisterMap.getOrDefault(operand, -1); // Use -1 to indicate not mapped

        if (regNum >= 0 && regNum < ALLOCATABLE_REGS.length) {
            return ALLOCATABLE_REGS[regNum]; // e.g., "$t0"
        } else {
            return ""; // Indicates spilled or not allocated in this block
        }
    }

    // --- NEW: Helper to get stack offset ---
    // Mimics logic from GreedyAllocator.java::getOff
    private int getOff(String virtReg) {
         Integer offset = this.stackOffsets.get(virtReg);
         if (offset == null) {
              // This case should ideally be handled by addAllVarsToStackMap ensuring all vars have slots
             output.println("    # ERROR: Stack offset requested for unknown variable: " + virtReg);
             return -1; // Or throw an error
         }
        return offset;
    }


    // --- UNCHANGED HELPER METHODS ---

    private String getMIPSOp(IRInstruction.OpCode opCode) {
        switch (opCode) {
            case ADD: return "add";
            case SUB: return "sub";
            case MULT: return "mul"; // Changed from "mult" based on GreedyAllocator.java
            case DIV: return "div";
            case AND: return "and";
            case OR: return "or";
            default: return "nop"; // Should not happen for binary ops
        }
    }

    // Updated to take String based on GreedyAllocator.java
    private String getMIPSBranchOp(String irOp) {
        // Assume irOp is already lowercase string like "breq", "brneq", etc.
        Map<String, String> branchMap = Map.of(
                "breq", "beq",
                "brneq", "bne",
                "brlt", "blt",
                "brgt", "bgt",
                "brgeq", "bge"
                // Add "brleq" mapping if needed: "brleq", "ble"
        );
        return branchMap.getOrDefault(irOp, "nop"); // Default to nop if mapping not found
    }

    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.matches("-?\\d+");
    }

     private boolean isBranch(IRInstruction.OpCode opCode) {
         return opCode == IRInstruction.OpCode.BREQ ||
                opCode == IRInstruction.OpCode.BRNEQ ||
                opCode == IRInstruction.OpCode.BRLT ||
                opCode == IRInstruction.OpCode.BRGT ||
                opCode == IRInstruction.OpCode.BRGEQ;
     }


    // --- Basic Block Logic (Adapted from previous version) ---

    /**
     * Represents a Basic Block, a sequence of IR instructions.
     */
    private static class MIPSBasicBlock {
        public List<IRInstruction> instructions = new ArrayList<>();
        public List<MIPSBasicBlock> successors = new ArrayList<>();
        public List<MIPSBasicBlock> predecessors = new ArrayList<>();
        public int startLine;
        public int endLine;
        // Add fields needed for allocation if moved here
        // Map<String, Integer> usageCounts;
        // Map<String, Integer> registerMap;
    }

    private List<MIPSBasicBlock> buildBasicBlocks(IRFunction func, Map<String, String> labelMap) {
        List<MIPSBasicBlock> blocks = new ArrayList<>();
        Map<String, MIPSBasicBlock> labelToBlock = new HashMap<>();
        Set<Integer> leaders = new HashSet<>();
        if (!func.instructions.isEmpty()) {
             leaders.add(0); // First instruction is a leader
        } else {
             return blocks; // No instructions, no blocks
        }

        // Pre-pass to map labels to instruction indices
        Map<String, Integer> labelToInstructionIndex = new HashMap<>();
        for (int i = 0; i < func.instructions.size(); i++) {
            IRInstruction instr = func.instructions.get(i);
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                labelToInstructionIndex.put(((IRLabelOperand) instr.operands[0]).getName(), i);
            }
        }

        // Find all leaders
        for (int i = 0; i < func.instructions.size(); i++) {
            IRInstruction instr = func.instructions.get(i);

            // Instruction after branch/jump/return is a leader
             if ((isBranch(instr.opCode) || instr.opCode == IRInstruction.OpCode.GOTO || instr.opCode == IRInstruction.OpCode.RETURN)
                 && i + 1 < func.instructions.size()) {
                 leaders.add(i + 1);
             }


            // Label instruction is a leader
            if (instr.opCode == IRInstruction.OpCode.LABEL) {
                leaders.add(i);
            }

            // Target of a branch is a leader
             if (isBranch(instr.opCode) || instr.opCode == IRInstruction.OpCode.GOTO) {
                 String labelName = ((IRLabelOperand) instr.operands[0]).getName();
                 Integer targetIndex = labelToInstructionIndex.get(labelName);
                 if (targetIndex != null) {
                     leaders.add(targetIndex);
                 }
             }
        }

        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        Collections.sort(sortedLeaders);

        // Create blocks from leaders
        for (int i = 0; i < sortedLeaders.size(); i++) {
            MIPSBasicBlock block = new MIPSBasicBlock();
            int start = sortedLeaders.get(i);
            int end = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) : func.instructions.size();

            block.instructions.addAll(func.instructions.subList(start, end));

            if (!block.instructions.isEmpty()) {
                 block.startLine = block.instructions.get(0).irLineNumber;
                 // Handle blocks with only one instruction
                 if (block.instructions.size() > 0) {
                      block.endLine = block.instructions.get(block.instructions.size() - 1).irLineNumber;
                 } else {
                      block.endLine = block.startLine; // Or handle as appropriate
                 }

                blocks.add(block);

                // Map first label to this block
                IRInstruction firstInstr = block.instructions.get(0);
                if (firstInstr.opCode == IRInstruction.OpCode.LABEL) {
                    String labelName = ((IRLabelOperand) firstInstr.operands[0]).getName();
                    labelToBlock.put(labelName, block);
                }
            }
        }

         // Link blocks (add successors/predecessors)
         for (int i = 0; i < blocks.size(); i++) {
             MIPSBasicBlock block = blocks.get(i);
             if (block.instructions.isEmpty()) continue;

             IRInstruction lastInstr = block.instructions.get(block.instructions.size() - 1);

             // Handle branches/gotos
             if (isBranch(lastInstr.opCode) || lastInstr.opCode == IRInstruction.OpCode.GOTO) {
                 String label = ((IRLabelOperand) lastInstr.operands[0]).getName();
                 MIPSBasicBlock target = labelToBlock.get(label);
                 if (target != null) {
                     block.successors.add(target);
                     target.predecessors.add(block);
                 } else {
                      output.println("# WARNING: Branch target label '" + label + "' not found for linking blocks.");
                 }
             }

             // Fall-through to next block (unless it's an unconditional jump or return)
             if (lastInstr.opCode != IRInstruction.OpCode.GOTO &&
                 lastInstr.opCode != IRInstruction.OpCode.RETURN &&
                 i < blocks.size() - 1) {
                 MIPSBasicBlock nextBlock = blocks.get(i + 1);
                 block.successors.add(nextBlock);
                 nextBlock.predecessors.add(block);
             }
         }
        return blocks;
    }

    /**
     * Counts the number of times each virtual register is USED in a block.
     */
    private Map<String, Integer> countUsesInBlock(MIPSBasicBlock block) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> definedInBlock = new HashSet<>();

        for (IRInstruction instr : block.instructions) {
            IROperand[] operands = instr.operands;
             IROperand definedOperand = getDefinedOperand(instr);
             if (definedOperand instanceof IRVariableOperand) {
                  definedInBlock.add(definedOperand.toString());
             }


             // Iterate through operands to find uses
             for (int i = 0; i < operands.length; i++) {
                 IROperand op = operands[i];
                 if (op instanceof IRVariableOperand) {
                     String varName = op.toString();
                      // Check if this operand is a use based on instruction type and position
                     if (isUseOperand(instr.opCode, i, operands.length)) {
                         incrementUsage(counts, varName);
                     }
                 }
             }
        }
        return counts;
    }

     // Helper to determine if an operand at a specific index is typically a "use"
     private boolean isUseOperand(IRInstruction.OpCode opCode, int operandIndex, int totalOperands) {
         switch (opCode) {
             case ADD: case SUB: case MULT: case DIV: case AND: case OR:
                 return operandIndex == 1 || operandIndex == 2; // op1 and op2 are uses
             case ASSIGN:
                 // Simple assign (len=2): op1 is use. Array assign (len>2) is handled differently (or consider op1, op2 as uses).
                 return operandIndex == 1 && totalOperands == 2; // Only the source in simple assign
             case GOTO:
                 return false; // Label is not a variable use
             case BREQ: case BRNEQ: case BRLT: case BRGT: case BRGEQ:
                 return operandIndex == 1 || operandIndex == 2; // op1 and op2 are uses
             case RETURN:
                 return operandIndex == 0; // The return value is a use
             case CALL:
                 return operandIndex > 0; // All operands after the function name are uses (args)
             case CALLR:
                 return operandIndex > 1; // All operands after dest and function name are uses (args)
             case ARRAY_STORE:
                 // value (0), array (1), index (2) are uses
                 return operandIndex == 0 || operandIndex == 1 || operandIndex == 2;
             case ARRAY_LOAD:
                 // array (1), index (2) are uses. dest (0) is a def.
                 return operandIndex == 1 || operandIndex == 2;
             case LABEL:
                 return false;
             default:
                 return false;
         }
     }

     // Helper to get the operand defined by an instruction (if any)
     private IROperand getDefinedOperand(IRInstruction instruction) {
         if (instruction.operands == null || instruction.operands.length == 0) {
             return null;
         }
         switch (instruction.opCode) {
             case ADD: case SUB: case MULT: case DIV: case AND: case OR:
             case ASSIGN: // Covers both simple and array assignments for the destination variable/array
             case CALLR: // First operand is the destination
             case ARRAY_LOAD: // First operand is the destination
                 return instruction.operands[0];
             // Other instructions like GOTO, branches, RETURN, CALL, ARRAY_STORE, LABEL don't define a simple variable result in operand[0]
             default:
                 return null;
         }
     }


    private void incrementUsage(Map<String, Integer> counts, String varName) {
        counts.put(varName, counts.getOrDefault(varName, 0) + 1);
    }

    /**
     * Performs greedy register allocation for a block based on usage counts.
     * Returns Map<String, Integer> where Integer is register number (0-7) or >=8 for spill.
     */
    private Map<String, Integer> allocateRegistersGreedy(Map<String, Integer> usageCounts) {
        Map<String, Integer> result = new HashMap<>();
        int availableRegCount = ALLOCATABLE_REGS.length; // Number of registers like $t0-$t7
        int currentReg = 0; // Index for ALLOCATABLE_REGS
        int spillIndex = availableRegCount; // Spill indices start after allocatable regs

        // Create a list of variables sorted by usage count (descending)
        List<Map.Entry<String, Integer>> sortedVars = new ArrayList<>(usageCounts.entrySet());
        // Sort by value (usage count) descending
        sortedVars.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));

        for (Map.Entry<String, Integer> entry : sortedVars) {
            String virtualReg = entry.getKey();
            if (currentReg < availableRegCount) {
                // Allocate a register (store its index 0-7)
                result.put(virtualReg, currentReg++);
            } else {
                // No registers left, spill (assign spill index >= 8)
                result.put(virtualReg, spillIndex++);
            }
        }
        return result;
    }
}