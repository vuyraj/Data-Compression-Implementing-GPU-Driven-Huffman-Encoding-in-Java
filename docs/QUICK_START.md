# Quick Start Guide

Get DataComp running in 5 minutes!

## Prerequisites Check

```bash
# 1. Verify Java 21
java -version
# Should show: openjdk version "21" or higher

# 2. Check GPU (optional, for GPU acceleration)
nvidia-smi     # For NVIDIA GPUs
# OR
clinfo         # For AMD/Intel GPUs
```

## Build and Run

```bash
# 1. Navigate to project
cd /Users/ubasolution/Downloads/Datacomp

# 2. Make gradlew executable
chmod +x gradlew

# 3. Build project
./gradlew clean build

# 4. Run application
./gradlew run
```

## First Compression

### Using the UI

1. Launch the application (see above)
2. Click **"Compress"** tab
3. Drag and drop a file OR click **"Select File"**
4. Click **"Compress"** button
5. Watch progress and throughput!

### Using CLI (if implemented)

```bash
# Compress a file
./gradlew run --args="compress input.txt output.dcz"

# Decompress
./gradlew run --args="decompress output.dcz restored.txt"
```

## Test GPU Acceleration

```bash
# 1. Generate test file (100 MB)
./gradlew generateLargeTestFile -PsizeMB=100

# 2. Run benchmark
./gradlew runBenchmark -PtestFile=test-data-100mb.bin

# Expected output:
# CPU: ~80 MB/s
# GPU: ~300 MB/s (if GPU available)
```

## Configuration

Edit `app/src/main/resources/application.conf`:

```hocon
datacomp {
    compression {
        chunk-size-mb = 32    # Adjust for your GPU memory
    }
    
    gpu {
        auto-detect = true    # Auto-detect GPU
        force-cpu = false     # Set to true to force CPU mode
    }
}
```

## Troubleshooting

### "GPU not available"
- Check GPU drivers: `nvidia-smi` or `clinfo`
- See `INSTALL.md` for detailed GPU setup
- Force CPU mode: set `gpu.force-cpu = true` in config

### Build fails
```bash
# Clean and rebuild
./gradlew clean build --refresh-dependencies
```

### Tests fail
```bash
# Run only CPU tests (skip GPU)
./gradlew test --tests "*.cpu.*"
```

## Next Steps

- 📖 Read [README.md](README.md) for full features
- 🔧 Follow [INSTALL.md](INSTALL.md) for GPU setup
- 📊 Review [docs/BENCHMARKING.md](docs/BENCHMARKING.md) for performance tuning

## Common Commands

```bash
# Build without tests
./gradlew build -x test

# Run tests only
./gradlew test

# Create distribution
./gradlew installDist
# Binary: ./app/build/install/app/bin/app

# Generate 1 GB test file
./gradlew generateLargeTestFile -PsizeMB=1024

# Run with more memory
export GRADLE_OPTS="-Xmx4g"
./gradlew run
```

## Success Indicators

✅ Application launches and shows "GPU: Available" (or "CPU" if no GPU)  
✅ File compression completes with progress bar  
✅ Benchmark shows CPU and GPU results (if GPU available)  
✅ Tests pass (`./gradlew test`)  

Enjoy GPU-accelerated compression! 🚀

