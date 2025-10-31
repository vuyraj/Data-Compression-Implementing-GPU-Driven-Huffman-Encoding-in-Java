# DataComp - GPU-Accelerated File Compression

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

DataComp is a production-ready, high-performance file compression application that leverages GPU acceleration via TornadoVM to achieve exceptional throughput (100+ MB/s) on large files (10 GB+). It features a modern JavaFX UI, canonical Huffman coding, and comprehensive benchmarking tools.

## ✨ Features

- **GPU Acceleration**: TornadoVM-powered GPU kernels for parallel histogram computation and encoding
- **High Performance**: 100+ MB/s compression/decompression throughput
- **Large File Support**: Streaming architecture handles 10 GB+ files efficiently
- **Modern UI**: Dark-themed JavaFX interface with drag-and-drop support
- **Chunked Processing**: Memory-efficient streaming with resume capability
- **Comprehensive Benchmarking**: CPU vs GPU performance comparison
- **Data Integrity**: SHA-256 checksums per chunk and globally
- **Resumable Operations**: Continue interrupted compression/decompression
- **Multi-threaded CPU Fallback**: Automatic fallback if GPU unavailable

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        JavaFX UI Layer                       │
│  (Dashboard, Compress, Benchmark, Settings)                  │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────────┐
│                   Service Layer                              │
│  ┌──────────────────┐         ┌──────────────────┐          │
│  │  CPU Services    │         │   GPU Services   │          │
│  │  - Compression   │◄────────┤  - TornadoVM     │          │
│  │  - Histogram     │ Fallback│  - GPU Kernels   │          │
│  └──────────────────┘         └──────────────────┘          │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────┴────────────────────────────────────┐
│                   Core Compression Layer                     │
│  - Canonical Huffman Coding                                  │
│  - Chunked Streaming I/O                                     │
│  - Checksum Verification                                     │
└──────────────────────────────────────────────────────────────┘
```

## 📋 Requirements

### Software
- **Java 21+** (OpenJDK or Oracle JDK)
- **Gradle 8.x** (included via wrapper)
- **TornadoVM** (for GPU support)
- **CUDA Toolkit 11.8+** or **OpenCL 2.0+** runtime

### Hardware
- **CPU**: Multi-core processor (4+ cores recommended)
- **GPU** (optional): NVIDIA GPU with CUDA support or AMD/Intel GPU with OpenCL
- **RAM**: 4 GB minimum, 8 GB+ recommended
- **Disk**: Fast SSD recommended for large files

## 🚀 Quick Start

### 1. Clone and Build

```bash
git clone https://github.com/yourusername/datacomp.git
cd datacomp
./gradlew clean build
```

### 2. Run the Application

```bash
./gradlew run
```

Or create a distribution:

```bash
./gradlew installDist
./app/build/install/app/bin/app
```

### 3. Compress a File

**Via UI:**
1. Click "Compress" tab
2. Drag and drop your file
3. Click "Compress"

**Via CLI:**
```bash
java -jar app/build/libs/app.jar compress input.bin output.dcz
```

## 📊 Benchmarking

Run CPU vs GPU benchmarks:

```bash
./gradlew runBenchmark -PtestFile=/path/to/large/file.bin
```

Or use the UI:
1. Click "Benchmark" tab
2. Select test file
3. Click "Run Benchmark"

### Sample Results

| Service | File Size | Throughput | Compression Ratio |
|---------|-----------|------------|-------------------|
| CPU (12 cores) | 1 GB | 85 MB/s | 58% |
| GPU (RTX 3080) | 1 GB | 342 MB/s | 58% |
| **Speedup** | - | **4.0x** | - |

## 🔧 Configuration

Edit `app/src/main/resources/application.conf`:

```hocon
datacomp {
    compression {
        chunk-size-mb = 32         # Chunk size (16-128 MB)
        cpu-threads = 0            # 0 = auto-detect
    }
    
    gpu {
        auto-detect = true         # Auto-detect GPU
        force-cpu = false          # Force CPU mode
        preferred-device = "any"   # "cuda", "opencl", or "any"
        fallback-on-error = true   # Fallback to CPU on GPU error
    }
}
```

## 🧪 Testing

Run all tests:
```bash
./gradlew test
```

Run GPU tests (requires GPU):
```bash
./gradlew testGpu
```

Run property-based tests:
```bash
./gradlew test --tests "*PropertyTest"
```

## 📦 File Format

DataComp uses a custom `.dcz` format:

```
┌─────────────────────────────────────────────────────┐
│ Header                                               │
│  - Magic Number (0x44435A46)                        │
│  - Version                                           │
│  - Original filename, size, timestamp                │
│  - Chunk table (offsets, sizes, checksums)          │
│  - Canonical codebooks per chunk                    │
│  - Global SHA-256 checksum                          │
├─────────────────────────────────────────────────────┤
│ Chunk 0 (compressed data)                           │
├─────────────────────────────────────────────────────┤
│ Chunk 1 (compressed data)                           │
├─────────────────────────────────────────────────────┤
│ ...                                                  │
└─────────────────────────────────────────────────────┘
```

## 🖼️ UI Screenshots

### Dashboard
The dashboard displays GPU status, system information, and recent performance metrics.

### Compress View
Drag-and-drop interface with real-time progress, throughput, and ETA display.

### Benchmark View
CPU vs GPU comparison with interactive charts showing throughput and compression ratios.

## 🎯 Performance Tips

1. **Chunk Size**: Use 32-64 MB chunks for optimal GPU utilization
2. **SSD**: Fast storage reduces I/O bottleneck
3. **GPU Memory**: Larger GPU memory allows bigger chunks
4. **Multi-GPU**: Multiple GPUs can process chunks in parallel (experimental)
5. **Compressibility**: Highly repetitive data compresses better

## 🐛 Troubleshooting

### GPU Not Detected
- Verify CUDA/OpenCL drivers installed
- Check TornadoVM configuration
- See `INSTALL.md` for detailed setup

### Out of Memory
- Reduce chunk size in configuration
- Increase JVM heap: `-Xmx8g`

### Slow Performance
- Enable GPU acceleration
- Use SSD for I/O
- Check CPU usage (should be distributed across cores)

## 📚 Additional Documentation

- [INSTALL.md](INSTALL.md) - Detailed installation instructions
- [docs/GPU_TUNING.md](docs/GPU_TUNING.md) - GPU performance tuning guide
- [docs/LARGE_FILES.md](docs/LARGE_FILES.md) - Best practices for huge files
- [docs/BENCHMARKING.md](docs/BENCHMARKING.md) - Benchmark methodology

## 🤝 Contributing

Contributions welcome! Please read CONTRIBUTING.md first.

## 📄 License

MIT License - see LICENSE file for details.

## 🙏 Acknowledgments

- TornadoVM team for GPU acceleration framework
- JavaFX community for UI toolkit
- OpenJDK project for Java platform

## 📞 Contact

- Issues: https://github.com/yourusername/datacomp/issues
- Email: support@datacomp.io

---

**Built with ❤️ and GPU power**

