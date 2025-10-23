#!/bin/bash

# Usage: run.sh path/to/file.ir --naive|--greedy
# Produces out.s in the current directory

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <input.ir> --naive|--greedy"
    echo ""
    echo "Examples:"
    echo "  ./run.sh public_test_cases/quicksort/quicksort.ir --naive"
    echo "  ./run.sh public_test_cases/prime/prime.ir --greedy"
    exit 1
fi

INPUT_FILE="$1"
ALLOCATION_FLAG="$2"

# Validate allocation flag
if [ "$ALLOCATION_FLAG" != "--naive" ] && [ "$ALLOCATION_FLAG" != "--greedy" ]; then
    echo "Error: Second argument must be --naive or --greedy"
    exit 1
fi

# Check if input file exists
if [ ! -f "$INPUT_FILE" ]; then
    echo "Error: Input file '$INPUT_FILE' not found"
    exit 1
fi

# Check if build directory exists
if [ ! -d "build" ]; then
    echo "Error: build/ directory not found. Run build.sh first."
    exit 1
fi

# Run the code generator
java -cp build main.java.mips.backend.MIPSCodeGenerator "$INPUT_FILE" "$ALLOCATION_FLAG" > out.s

if [ $? -eq 0 ]; then
    echo "Generated out.s successfully"
    echo ""
    echo "To test the generated assembly:"
    echo "  java -cp build main.java.mips.MIPSInterpreter out.s"
    echo ""
    echo "With input file:"
    echo "  java -cp build main.java.mips.MIPSInterpreter out.s < input.in"
else
    echo "Error during code generation"
    exit 1
fi