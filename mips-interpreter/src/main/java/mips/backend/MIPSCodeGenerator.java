package main.java.mips.backend;

import ir.*;
import java.io.PrintStream;

public class MIPSCodeGenerator {
    private IRProgram program;
    private PrintStream output;
    private boolean useGreedy;
    
    public MIPSCodeGenerator(IRProgram program, PrintStream output, boolean useGreedy) {
        this.program = program;
        this.output = output;
        this.useGreedy = useGreedy;
    }
    
    public void generate() {
        if (useGreedy) {
            output.println("# Greedy allocation not yet implemented");
            MIPSNaiveAllocator allocator = new MIPSNaiveAllocator(output);
            allocator.generateProgram(program);
        } else {
            MIPSNaiveAllocator allocator = new MIPSNaiveAllocator(output);
            allocator.generateProgram(program);
        }
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MIPSCodeGenerator <input.ir> --naive|--greedy");
            System.exit(1);
        }
        
        String inputFile = args[0];
        boolean useGreedy = args[1].equals("--greedy");
        
        IRReader reader = new IRReader();
        IRProgram program = reader.parseIRFile(inputFile);
        
        MIPSCodeGenerator generator = new MIPSCodeGenerator(program, System.out, useGreedy);
        generator.generate();
    }
}