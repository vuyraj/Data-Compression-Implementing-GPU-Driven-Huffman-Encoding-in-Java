# GPU Performance Tuning Guide

This guide helps you optimize DataComp's GPU performance for your specific hardware.

## Understanding GPU Utilization

### Key Metrics

1. **Throughput (MB/s)**: Data processed per second
2. **GPU Utilization (%)**: How much of GPU compute is used
3. **Memory Bandwidth (GB/s)**: Data transfer rate
4. **Occupancy (%)**: Ratio of active to max threads

### Monitoring Tools

```bash
# NVIDIA GPUs
nvidia-smi dmon -s u

# AMD GPUs
rocm-smi --showuse

# During benchmark
watch -n 1 nvidia-smi
```

## Chunk Size Optimization

Chunk size significantly impacts GPU performance.

### Guidelines

| GPU Memory | Recommended Chunk Size |
|------------|----------------------|
| 4 GB       | 16-32 MB             |
| 6 GB       | 32-48 MB             |
| 8 GB+      | 48-64 MB             |
| 12 GB+     | 64-128 MB            |

### Testing Chunk Sizes

```bash
# Test different chunk sizes
for size in 16 32 48 64; do
    echo "Testing ${size}MB chunks..."
    # Edit config: compression.chunk-size-mb = $size
    ./gradlew runBenchmark -PtestFile=large-file.bin
done
```

### Configuration

Edit `app/src/main/resources/application.conf`:

```hocon
compression {
    chunk-size-mb = 48  # Adjust based on GPU memory
}
```

## Device Selection

If you have multiple GPUs, select the optimal one.

### List Available Devices

```bash
# Via application
./gradlew run -PlistDevices

# Via nvidia-smi
nvidia-smi -L
```

### Select Device

```hocon
gpu {
    device-index = 0  # 0 = first GPU, 1 = second, etc.
}
```

## Memory Management

### GPU Memory Limits

Prevent out-of-memory errors:

```hocon
gpu {
    memory-limit-mb = 6144  # Reserve 2GB for system
}
```

### Host Memory

Increase JVM heap for large files:

```bash
export GRADLE_OPTS="-Xmx8g"
./gradlew run
```

Or edit `app/build.gradle`:

```gradle
application {
    applicationDefaultJvmArgs = [
        '-Xmx8g',
        // ... other args
    ]
}
```

## Kernel Optimization

### Work Group Size

For advanced users, TornadoVM work group size can be tuned:

```bash
export TORNADO_OPTIONS="-Dtornado.threadInfo"
```

Optimal work group sizes:
- **NVIDIA**: 256 or 512 threads
- **AMD**: 256 threads
- **Intel**: 128 threads

### Data Transfer Optimization

Minimize PCIe transfers:

```hocon
compression {
    use-memory-mapped-io = true  # Reduce CPU-GPU transfers
}
```

## Platform-Specific Tuning

### NVIDIA (CUDA)

```bash
# Enable async copies
export TORNADO_OPTIONS="-Dtornado.ptx.async=true"

# Use unified memory (Pascal+)
export CUDA_MANAGED_FORCE_DEVICE_ALLOC=1
```

### AMD (ROCm)

```bash
# Set GPU frequency
sudo rocm-smi --setperflevel high

# Monitor
rocm-smi --showpower --showtemp
```

### Intel (OpenCL)

```bash
# Enable profiling
export TORNADO_OPTIONS="-Dtornado.opencl.profiling=true"
```

## Benchmarking Best Practices

### Warmup

Always include warmup iterations:

```hocon
benchmark {
    warmup-iterations = 5      # Warm up GPU
    measurement-iterations = 10
}
```

### Representative Data

Test with realistic data:

```bash
# Random (worst case)
./gradlew generateLargeTestFile -PsizeMB=1024

# Compressible (best case)
./gradlew generateLargeTestFile -PsizeMB=1024 --compressible
```

### Multiple Runs

Run multiple benchmarks for statistical significance:

```bash
for i in {1..10}; do
    ./gradlew runBenchmark >> results.txt
done
```

## Common Issues

### Low GPU Utilization

**Symptoms**: GPU usage < 50%

**Causes**:
- Chunk size too small
- I/O bottleneck (slow disk)
- PCIe bandwidth limit

**Solutions**:
1. Increase chunk size
2. Use SSD
3. Use NVMe if available

### Out of Memory

**Symptoms**: `OutOfMemoryError` or GPU allocation failure

**Solutions**:
1. Reduce chunk size
2. Set memory limit
3. Close other GPU applications

### Slow Performance

**Symptoms**: GPU slower than CPU

**Causes**:
- Data transfer overhead
- Small file size
- GPU not properly utilized

**Solutions**:
1. Use larger test files (>100 MB)
2. Verify GPU drivers updated
3. Check thermal throttling

## Performance Targets

### Expected Throughput

| Configuration | Compression | Decompression |
|---------------|-------------|---------------|
| CPU (8 cores) | 60-100 MB/s | 80-120 MB/s   |
| GPU (Mid-range) | 150-250 MB/s | 200-300 MB/s |
| GPU (High-end) | 300-500 MB/s | 400-600 MB/s |

### Speedup Targets

- **Minimum**: 2x CPU performance
- **Typical**: 3-4x CPU performance
- **Optimal**: 5-6x CPU performance

## Advanced Configuration

### Multi-GPU (Experimental)

For multiple GPUs:

```hocon
gpu {
    multi-gpu-enabled = true
    gpu-count = 2  # Number of GPUs to use
}
```

### Profiling

Enable detailed profiling:

```bash
export TORNADO_OPTIONS="-Dtornado.profiling=true -Dtornado.debug=true"
./gradlew runBenchmark
```

### Custom Kernels

For developers modifying kernels:

```java
// In TornadoKernels.java
@Parallel
public static void customHistogramKernel(...) {
    // Your optimized implementation
}
```

## Monitoring Dashboard

Create a monitoring script:

```bash
#!/bin/bash
# gpu-monitor.sh

while true; do
    clear
    echo "=== GPU Status ==="
    nvidia-smi --query-gpu=utilization.gpu,utilization.memory,temperature.gpu \
               --format=csv,noheader
    sleep 1
done
```

Run during benchmarks:

```bash
./gpu-monitor.sh &
./gradlew runBenchmark
```

## Getting Help

If performance is below expectations:

1. Check hardware: `nvidia-smi` or `rocm-smi`
2. Review logs: `logs/datacomp.log`
3. Run diagnostics: `./gradlew testGpu --info`
4. Report issue with:
   - GPU model
   - Driver version
   - Benchmark results
   - Configuration file

## References

- [TornadoVM Performance Guide](https://tornadovm.readthedocs.io/en/latest/performance.html)
- [CUDA Best Practices](https://docs.nvidia.com/cuda/cuda-c-best-practices-guide/)
- [OpenCL Optimization](https://www.khronos.org/opencl/)

