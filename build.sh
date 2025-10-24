#!/bin/bash

BUILD_DIR="build"

# Detect OS and set classpath separator
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OSTYPE" == "win32" ]]; then
  CP_SEPARATOR=";"
else
  CP_SEPARATOR=":"
fi

# Clean previous build
rm -rf "$BUILD_DIR"

# Create build directory
mkdir -p "$BUILD_DIR"

echo "Building Java compiler backend..."

# Compile all sources directly (no temp file)
echo "Compiling all Java sources..."
javac -d "$BUILD_DIR" \
      -cp "src${CP_SEPARATOR}mips-interpreter/src${CP_SEPARATOR}$BUILD_DIR" \
      $(find src mips-interpreter/src -type f -name "*.java")

# Check compilation result
if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo ""
echo "Build complete! Output in $BUILD_DIR/"
echo ""
echo "Quick test commands:"
echo "  # Run existing MIPS interpreter:"
echo "  java -cp $BUILD_DIR main.java.mips.MIPSInterpreter mips-interpreter/tests/hello.s"
echo ""
echo "  # Generate MIPS code (naive):"
echo "  ./run.sh public_test_cases/quicksort/quicksort.ir --naive"
echo ""
echo "  # Test generated code:"
echo "  java -cp $BUILD_DIR main.java.mips.MIPSInterpreter out.s < public_test_cases/quicksort/0.in"