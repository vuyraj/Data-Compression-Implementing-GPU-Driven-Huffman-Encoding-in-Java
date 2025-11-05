# GPU Troubleshooting Guide

## Current Issue: OpenCL Driver Errors

You're experiencing OpenCL driver crashes with these errors:
- `clCreateCommandQueue -> -9999` (Driver internal error)
- `clEnqueueWriteBuffer -> -36` (Invalid command queue)
- `clFinish -> -36` (Invalid command queue)
- `SIGSEGV in libOpenCL.so.1` (Native library crash)

## Root Causes

1. **OpenCL driver corruption** - Driver state is corrupted
2. **NVIDIA driver incompatibility** - OpenCL implementation has bugs
3. **GPU memory exhaustion** - Previous runs didn't clean up properly
4. **Multiple OpenCL instances** - Competing access to GPU

## Immediate Solutions

### Option 1: Force CPU Mode (Recommended)
The application is now configured to use CPU-only mode:

**File: `app/src/main/resources/application.conf`**
```properties
gpu {
    force-cpu = true  # ✅ Already set
}
```

Now run:
```bash
./gradlew clean build
./gradlew runTornado
```

The application will use CPU compression (still very fast with multi-threading).

### Option 2: Restart Your System
```bash
sudo reboot
```

This resets the OpenCL driver state and often fixes corruption issues.

### Option 3: Update NVIDIA Drivers
```bash
# Check current driver version
nvidia-smi

# Update to latest NVIDIA drivers
sudo apt update
sudo apt install nvidia-driver-535  # or latest available

# Reboot
sudo reboot
```

### Option 4: Reinstall OpenCL Runtime
```bash
# Remove old OpenCL runtime
sudo apt remove --purge nvidia-opencl-*

# Reinstall
sudo apt install nvidia-opencl-icd-535

# Verify
clinfo
```

## Verify GPU Configuration

### Check OpenCL Status
```bash
# List OpenCL devices
clinfo

# Check if MX330 is detected
nvidia-smi
```

### Test TornadoVM
```bash
# Set environment
export PATH=/opt/TornadoVM/bin/sdk/bin:$PATH

# Test TornadoVM
tornado --devices

# Run simple test
tornado --printKernel uk.ac.manchester.tornado.examples.HelloWorld
```

## Long-term Solutions

### 1. Fix GPU Encoding (When Driver Works)
The GPU encoding kernels have bit-packing bugs. Once OpenCL is stable:
1. Set `force-cpu = false` in config
2. Fix `writeCodewordsOptimizedKernel()` in `TornadoKernels.java`
3. Test with small files first

### 2. Adjust Chunk Size for GPU
For MX330 (2GB VRAM):
- Current: 32 MB ✅ (safe)
- Can try: 64 MB (if memory allows)
- Avoid: 512 MB (causes OOM)

### 3. Monitor GPU Usage
```bash
# Watch GPU in real-time
watch -n 1 nvidia-smi

# Check memory usage
nvidia-smi --query-gpu=memory.used,memory.total --format=csv
```

## Current Status

✅ **CPU mode enabled** - Application will run without GPU
✅ **Chunk size optimized** - 32 MB for MX330
✅ **Error handling added** - Better error messages
❌ **GPU encoding disabled** - Has bit-packing bugs
⚠️  **OpenCL driver unstable** - Needs system restart or driver update

## Next Steps

1. **Try CPU mode first**: `./gradlew clean build && ./gradlew runTornado`
2. **If it works**: Your OpenCL driver is the issue
3. **Restart system** and try GPU mode again
4. **If still crashes**: Update NVIDIA drivers
5. **If still problems**: Use CPU mode permanently (still very fast)

## Performance Expectations

### CPU Mode (Current)
- Multi-threaded encoding: 2-4x speedup over single-thread
- Fork/Join parallelism for histogram
- Still very good performance

### GPU Mode (When Working)
- 10x faster histogram computation
- Same encoding speed (GPU encoding disabled due to bugs)
- Overall ~2x speedup vs CPU for large files

## Contact

If issues persist, check:
- NVIDIA driver version: `nvidia-smi`
- OpenCL platforms: `clinfo`
- TornadoVM devices: `tornado --devices`
- Java version: `java -version` (should be 21)
