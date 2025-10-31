# Benchmark Methodology

Comprehensive guide to benchmarking DataComp performance.

## Overview

Fair benchmarking requires:
- Consistent test data
- Proper warmup
- Multiple iterations
- Controlled environment
- Statistical analysis

## Test Data Preparation

### Types of Test Data

1. **Random Data** (worst-case compression)
   ```bash
   ./gradlew generateLargeTestFile -PsizeMB=1024
   ```

2. **Compressible Text** (best-case)
   ```bash
   ./gradlew generateLargeTestFile -PsizeMB=1024 --compressible
   ```

3. **Real-world Data** (realistic)
   - Log files
   - Database dumps
   - Media files
   - Source code

### Recommended Test Suite

| File Type | Size | Purpose |
|-----------|------|---------|
| Random    | 100 MB | Quick test |
| Random    | 1 GB | Standard benchmark |
| Random    | 10 GB | Large file test |
| Compressible | 1 GB | Best-case scenario |
| Real-world | Various | Production validation |

## Benchmark Configuration

### Warmup Phase

Critical for GPU benchmarks:

```hocon
benchmark {
    warmup-iterations = 5       # Allow GPU to reach full speed
    measurement-iterations = 10 # Collect stable measurements
}
```

### System Preparation

```bash
# 1. Close unnecessary applications
# 2. Disable CPU throttling
sudo cpupower frequency-set -g performance

# 3. Clear caches
sync; echo 3 > /proc/sys/vm/drop_caches

# 4. Set GPU to max performance
nvidia-smi -pm 1
nvidia-smi -lgc 2100  # Max GPU clock
```

## Running Benchmarks

### Basic Benchmark

```bash
# CPU only
./gradlew runBenchmark -PtestFile=test-1gb.bin -PforceCpu=true

# GPU
./gradlew runBenchmark -PtestFile=test-1gb.bin

# Both (comparison)
./gradlew runBenchmark -PtestFile=test-1gb.bin -Pcompare=true
```

### Detailed Profiling

```bash
# Enable profiling
export TORNADO_OPTIONS="-Dtornado.profiling=true"
./gradlew runBenchmark -PtestFile=test-1gb.bin -Ddetailed-profiling=true
```

### Output Formats

```hocon
benchmark {
    output-format = "console"  # or "json", "csv"
}
```

## Metrics

### Primary Metrics

1. **Throughput (MB/s)**
   ```
   Throughput = File Size (MB) / Duration (seconds)
   ```

2. **Compression Ratio**
   ```
   Ratio = Compressed Size / Original Size
   ```

3. **Speedup**
   ```
   Speedup = GPU Throughput / CPU Throughput
   ```

### Secondary Metrics

- **CPU Utilization** (%)
- **GPU Utilization** (%)
- **Memory Usage** (MB)
- **I/O Wait** (%)

### Per-stage Breakdown

- Frequency computation time
- Huffman tree building time
- Encoding time
- I/O time

## Analysis

### Statistical Measures

Calculate for multiple runs:

```python
import numpy as np

throughputs = [342, 338, 345, 340, 343]  # MB/s

mean = np.mean(throughputs)
std = np.std(throughputs)
cv = (std / mean) * 100  # Coefficient of variation

print(f"Throughput: {mean:.1f} ± {std:.1f} MB/s (CV: {cv:.1f}%)")
```

Acceptable CV: < 5%

### Comparison Table

| Configuration | Throughput | Ratio | Speedup |
|---------------|------------|-------|---------|
| CPU (8 cores) | 85 MB/s    | 58%   | 1.0x    |
| GPU (RTX 3080)| 342 MB/s   | 58%   | 4.0x    |

### Visualization

```bash
# Generate charts
./gradlew runBenchmark --output-format=json > results.json
python scripts/plot_results.py results.json
```

## Reproducibility

### Document Environment

```bash
# System info
./gradlew sysinfo > benchmark-env.txt

# Append:
echo "Date: $(date)" >> benchmark-env.txt
echo "Git: $(git rev-parse HEAD)" >> benchmark-env.txt
nvidia-smi >> benchmark-env.txt
```

### Benchmark Script

```bash
#!/bin/bash
# run-benchmarks.sh

SIZES=(100 500 1000 5000 10000)
ITERATIONS=10

for size in "${SIZES[@]}"; do
    echo "=== Benchmarking ${size}MB ==="
    
    # Generate test file
    ./gradlew generateLargeTestFile -PsizeMB=$size
    
    # Run benchmark
    for i in $(seq 1 $ITERATIONS); do
        echo "Iteration $i/$ITERATIONS"
        ./gradlew runBenchmark -PtestFile=test-data-${size}mb.bin \
            >> results-${size}mb.txt
    done
done

# Analyze
python scripts/analyze_results.py results-*.txt
```

## Performance Targets

### Minimum Acceptable

- CPU throughput: > 50 MB/s
- GPU speedup: > 2x CPU
- Compression ratio: 40-80% (depends on data)

### Production Goals

- CPU throughput: 80-120 MB/s
- GPU speedup: 3-5x CPU
- 10 GB file: < 2 minutes on GPU

### Optimal

- CPU throughput: 100+ MB/s
- GPU speedup: 5-7x CPU
- 10 GB file: < 1 minute on GPU

## Regression Testing

### Continuous Benchmarking

```yaml
# .github/workflows/benchmark.yml
name: Benchmark

on: [push, pull_request]

jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Run benchmarks
        run: |
          ./gradlew generateLargeTestFile -PsizeMB=100
          ./gradlew runBenchmark > results.txt
      - name: Compare
        run: |
          python scripts/compare_benchmark.py \
            results.txt baseline.txt
```

### Baseline Storage

```bash
# Save current results as baseline
./gradlew runBenchmark --output-format=json > baseline.json
git add baseline.json
git commit -m "Update benchmark baseline"
```

## Troubleshooting

### Inconsistent Results

**Symptoms**: High variance (CV > 10%)

**Causes**:
- Background processes
- Thermal throttling
- Power management
- Insufficient warmup

**Solutions**:
- Increase warmup iterations
- Monitor temperatures
- Disable power saving
- Close background apps

### Lower Than Expected

**Symptoms**: Below performance targets

**Checks**:
1. GPU detected? `nvidia-smi`
2. Correct chunk size? See [GPU_TUNING.md](GPU_TUNING.md)
3. I/O bottleneck? Monitor with `iotop`
4. Memory sufficient? Check with `free -h`

### GPU Not Faster

**Symptoms**: GPU slower than CPU

**Reasons**:
- Small file (< 10 MB): overhead dominates
- I/O bound: disk slower than compression
- PCIe bottleneck: data transfer overhead

**Verify**:
```bash
# Test GPU directly
./gradlew testGpu --tests "*GpuFrequencyServiceTest"
```

## Reporting

### Benchmark Report Template

```markdown
# DataComp Benchmark Report

## Environment
- **CPU**: AMD Ryzen 9 5950X (16 cores)
- **GPU**: NVIDIA RTX 3080 (10 GB)
- **RAM**: 32 GB DDR4-3600
- **Storage**: Samsung 980 PRO NVMe
- **OS**: Ubuntu 22.04 LTS
- **Driver**: NVIDIA 545.29.06
- **CUDA**: 12.3

## Configuration
- Chunk size: 64 MB
- Warmup iterations: 5
- Measurement iterations: 10

## Results

### 1 GB Random Data

| Service | Throughput | Std Dev | Speedup |
|---------|------------|---------|---------|
| CPU     | 85.3 MB/s  | 2.1     | 1.0x    |
| GPU     | 341.7 MB/s | 5.4     | 4.0x    |

### 10 GB Random Data

| Service | Throughput | Duration |
|---------|------------|----------|
| CPU     | 87.1 MB/s  | 117.4s   |
| GPU     | 356.2 MB/s | 28.7s    |

## Conclusion
GPU provides 4x speedup on large files, meeting production targets.
```

## Advanced Topics

### Multi-GPU Benchmarking

```bash
# Benchmark each GPU
for gpu in 0 1; do
    export CUDA_VISIBLE_DEVICES=$gpu
    ./gradlew runBenchmark -PtestFile=test-1gb.bin \
        > results-gpu$gpu.txt
done
```

### Power Efficiency

```bash
# Measure energy consumption
nvidia-smi --query-gpu=power.draw \
    --format=csv,noheader,nounits \
    -lms 100 > power.txt &
    
./gradlew runBenchmark

# Calculate energy
python scripts/energy_analysis.py power.txt
```

### Compression Ratio Analysis

```bash
# Test different data types
./gradlew runBenchmark -PtestFile=random.bin > random-results.txt
./gradlew runBenchmark -PtestFile=text.txt > text-results.txt
./gradlew runBenchmark -PtestFile=binary.bin > binary-results.txt
```

## References

- [Performance Analysis Tools](https://developer.nvidia.com/nsight-systems)
- [CUDA Profiling Guide](https://docs.nvidia.com/cuda/profiler-users-guide/)
- [TornadoVM Profiling](https://tornadovm.readthedocs.io/en/latest/profiler.html)

