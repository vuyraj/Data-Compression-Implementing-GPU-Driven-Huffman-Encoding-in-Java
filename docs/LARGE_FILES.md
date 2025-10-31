# Large File Handling Guide

Best practices for compressing and decompressing files larger than 1 GB.

## Overview

DataComp is designed to handle extremely large files (10 GB+) efficiently through:
- Chunked streaming architecture
- Memory-mapped I/O
- Resume capability
- Bounded memory usage

## Memory Requirements

### Formula

```
Required RAM = (Chunk Size × 3) + 512 MB

Examples:
- 32 MB chunks → 608 MB RAM
- 64 MB chunks → 704 MB RAM
- 128 MB chunks → 896 MB RAM
```

### Configuration

For 16 GB file on 8 GB RAM system:

```hocon
compression {
    chunk-size-mb = 32           # Conservative chunk size
    use-memory-mapped-io = true  # Enable memory mapping
}
```

## Chunk Size Selection

### Guidelines

| File Size | Recommended Chunks | Rationale |
|-----------|-------------------|-----------|
| < 100 MB  | 16 MB             | Fast processing |
| 100 MB - 1 GB | 32 MB         | Balanced |
| 1 GB - 10 GB | 48-64 MB       | GPU efficiency |
| 10 GB+    | 64-128 MB         | Minimize overhead |

### Trade-offs

**Small Chunks (16-32 MB)**
- ✅ Lower memory usage
- ✅ Better resume granularity
- ❌ More overhead
- ❌ Lower GPU utilization

**Large Chunks (64-128 MB)**
- ✅ Better GPU utilization
- ✅ Less overhead
- ❌ Higher memory usage
- ❌ Coarser resume points

## I/O Optimization

### SSD vs HDD

**SSD (Recommended)**
- 500+ MB/s sequential read/write
- Low latency
- Full GPU utilization possible

**HDD**
- 100-150 MB/s typical
- May bottleneck compression
- Consider larger chunks to amortize seeks

### NVMe

For NVMe SSDs (2000+ MB/s):
```hocon
compression {
    io-buffer-size-kb = 512  # Increase buffer
    chunk-size-mb = 128      # Larger chunks
}
```

## Progress Tracking

### Real-time Updates

Configure update frequency:

```hocon
ui {
    progress-update-interval-ms = 100  # Update every 100ms
}
```

### Throughput Monitoring

```bash
# Monitor during compression
watch -n 1 'ls -lh output.dcz'
```

## Resume Capability

### How It Works

1. DataComp saves checkpoint after each chunk
2. On interruption, last complete chunk index stored
3. Resume skips completed chunks

### Manual Resume

```bash
# If interrupted at chunk 150 out of 200
java -jar app.jar resume input.bin output.dcz --last-chunk 150
```

### Automatic Resume (Future)

UI will detect partial files and offer to resume.

## Error Handling

### Checksum Verification

Each chunk has SHA-256 checksum:
```hocon
output {
    verify-after-compress = true  # Verify each chunk
}
```

### Corruption Detection

DataComp will:
1. Detect corrupted chunks during decompression
2. Report specific chunk with error
3. Allow partial recovery

## Performance Tips

### 1. Pre-allocate Output File

```bash
# Reserve space (prevents fragmentation)
fallocate -l 10G output.dcz
```

### 2. Disable Indexing

```bash
# Temporarily disable file indexing
# Windows: Folder properties → Advanced → Indexing
# Linux: Add to .noindex
```

### 3. Use Dedicated Disk

- Compress from disk A to disk B (avoid contention)
- Separate input/output on different physical drives

### 4. Disable Antivirus

Temporarily disable for compression directory:
```bash
# Windows Defender
Add-MpPreference -ExclusionPath "C:\path\to\compression"
```

## Monitoring

### System Resources

```bash
# CPU and Memory
htop

# Disk I/O
iotop

# GPU
nvidia-smi dmon
```

### Application Logs

```bash
# Enable detailed logging
tail -f logs/datacomp.log

# Metrics
tail -f logs/metrics.log
```

## Example Workflows

### Workflow 1: Compress 50 GB Database Backup

```bash
# 1. Configure
cat > app/src/main/resources/application.conf << EOF
compression {
    chunk-size-mb = 64
    use-memory-mapped-io = true
}
gpu {
    auto-detect = true
}
EOF

# 2. Run compression
./gradlew run --args="compress /data/backup.sql /backup/backup.dcz"

# 3. Verify
./gradlew run --args="verify /backup/backup.dcz"
```

### Workflow 2: Compress Multiple Large Files

```bash
#!/bin/bash
# batch-compress.sh

for file in /data/*.bin; do
    output="${file%.bin}.dcz"
    echo "Compressing $file..."
    
    ./gradlew run --args="compress $file $output" \
        2>&1 | tee "logs/compress-$(basename $file).log"
    
    if [ $? -eq 0 ]; then
        echo "✓ Success: $file"
    else
        echo "✗ Failed: $file"
    fi
done
```

### Workflow 3: Network Transfer

```bash
# Compress and stream over network
./gradlew run --args="compress large.bin - " | \
    ssh remote "cat > compressed.dcz"
```

## Benchmarking Large Files

### Generate Test Data

```bash
# Generate 10 GB test file
./gradlew generateLargeTestFile -PsizeMB=10240

# Generate compressible data
./gradlew generateLargeTestFile -PsizeMB=10240 --compressible
```

### Run Benchmark

```bash
# Full benchmark
time ./gradlew runBenchmark -PtestFile=test-data-10240mb.bin

# Profile
./gradlew runBenchmark -PtestFile=test-data-10240mb.bin \
    -Ddetailed-profiling=true
```

## Troubleshooting

### Problem: Out of Memory

**Symptoms**: `OutOfMemoryError`, application crash

**Solutions**:
```bash
# 1. Reduce chunk size
compression.chunk-size-mb = 16

# 2. Increase heap
export GRADLE_OPTS="-Xmx8g"

# 3. Enable disk spilling (future feature)
compression.spill-to-disk = true
```

### Problem: Slow Compression

**Symptoms**: < 50 MB/s throughput

**Checklist**:
- [ ] GPU detected? Check dashboard
- [ ] SSD or HDD? Use SSD
- [ ] Disk space? Ensure adequate free space
- [ ] Antivirus? Temporarily disable
- [ ] Background tasks? Close unnecessary apps

### Problem: Disk Full

**Symptoms**: "No space left on device"

**Solutions**:
```bash
# 1. Check space
df -h

# 2. Compress to different disk
--output-dir /mnt/external/

# 3. Clean temp files
rm -rf /tmp/datacomp-*
```

## Advanced: Custom Chunking

For very specialized use cases:

```java
// Custom chunk strategy
public class CustomChunkStrategy implements ChunkStrategy {
    @Override
    public int getChunkSize(long fileSize) {
        if (fileSize < 1_000_000_000L) {
            return 32 * 1024 * 1024;  // 32 MB
        } else {
            return 128 * 1024 * 1024; // 128 MB
        }
    }
}
```

## FAQ

**Q: Can I compress files larger than available RAM?**  
A: Yes! DataComp uses streaming, so a 100 GB file can be compressed on a 4 GB RAM system.

**Q: How much disk space needed?**  
A: Input file size + expected compressed size (typically 50-80% of input).

**Q: Can compression be paused?**  
A: Use Ctrl+C to interrupt. Use resume feature to continue later.

**Q: Is multi-threaded on CPU?**  
A: Yes, CPU mode uses all available cores automatically.

**Q: Maximum file size?**  
A: Theoretical limit is 2^63 bytes (8 exabytes). Practical limit depends on available disk space.

## References

- [README.md](../README.md) - General usage
- [GPU_TUNING.md](GPU_TUNING.md) - GPU optimization
- [BENCHMARKING.md](BENCHMARKING.md) - Benchmark guide

