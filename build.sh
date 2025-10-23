#!/bin/bash

# Clean previous build
rm -rf build/

# Create build directory
mkdir -p build

# Compile IR parser and interpreter from src/
echo "Compiling IR classes..."
javac -d build -cp src \
    src/ir/datatype/*.java \
    src/ir/operand/*.java \
    src/ir/*.java \
    src/IRInterpreter.java \
    src/Demo.java

# Compile MIPS interpreter (existing code)
echo "Compiling MIPS interpreter..."
javac -d build -cp mips-interpreter/src \
    mips-interpreter/src/main/java/exceptions/*.java \
    mips-interpreter/src/main/java/mips/operand/*.java \
    mips-interpreter/src/main/java/mips/*.java

# Compile MIPS backend (new code generator)
echo "Compiling MIPS backend..."
javac -d build -cp src:mips-interpreter/src:build \
    mips-interpreter/src/main/java/mips/backend/*.java

echo ""
echo "Build complete. Output in build/"
echo ""
echo "Quick test commands:"
echo "  # Run existing MIPS interpreter:"
echo "  java -cp build main.java.mips.MIPSInterpreter mips-interpreter/tests/hello.s"
echo ""
echo "  # Generate MIPS code (naive):"
echo "  ./run.sh public_test_cases/quicksort/quicksort.ir --naive"
echo ""
echo "  # Test generated code:"
echo "  java -cp build main.java.mips.MIPSInterpreter out.s < public_test_cases/quicksort/0.in"