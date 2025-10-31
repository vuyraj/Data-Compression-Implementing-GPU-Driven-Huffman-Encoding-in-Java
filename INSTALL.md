# DataComp Installation Guide

This guide provides detailed instructions for installing DataComp and its dependencies on Linux and Windows.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Java Installation](#java-installation)
- [CUDA Installation (Linux)](#cuda-installation-linux)
- [CUDA Installation (Windows)](#cuda-installation-windows)
- [OpenCL Installation](#opencl-installation)
- [TornadoVM Installation](#tornadovm-installation)
- [Building DataComp](#building-datacomp)
- [Verification](#verification)

## Prerequisites

### System Requirements

- **OS**: Linux (Fedora 38+, Debian 12+, Ubuntu 22.04+) or Windows 10/11
- **CPU**: x86-64 with AVX2 support
- **GPU**: NVIDIA GPU (CUDA 11.8+) or AMD/Intel GPU (OpenCL 2.0+)
- **RAM**: 4 GB minimum, 8 GB+ recommended
- **Disk**: 10 GB free space

## Java Installation

### Linux (Fedora)

```bash
# Install OpenJDK 21
sudo dnf install java-21-openjdk java-21-openjdk-devel

# Verify installation
java -version
```

### Linux (Debian/Ubuntu)

```bash
# Add repository
sudo apt update
sudo apt install wget apt-transport-https

# Install OpenJDK 21
sudo apt install openjdk-21-jdk

# Verify installation
java -version
```

### Windows

1. Download OpenJDK 21 from [Adoptium](https://adoptium.net/)
2. Run installer and add to PATH
3. Verify in PowerShell:
   ```powershell
   java -version
   ```

## CUDA Installation (Linux)

### Fedora

```bash
# Add NVIDIA repository
sudo dnf config-manager --add-repo \
    https://developer.download.nvidia.com/compute/cuda/repos/fedora38/x86_64/cuda-fedora38.repo

# Install CUDA Toolkit
sudo dnf install cuda-toolkit-12-3

# Add to PATH
echo 'export PATH=/usr/local/cuda-12.3/bin:$PATH' >> ~/.bashrc
echo 'export LD_LIBRARY_PATH=/usr/local/cuda-12.3/lib64:$LD_LIBRARY_PATH' >> ~/.bashrc
source ~/.bashrc

# Verify installation
nvcc --version
nvidia-smi
```

### Debian/Ubuntu

```bash
# Download CUDA installer
wget https://developer.download.nvidia.com/compute/cuda/12.3.0/local_installers/cuda_12.3.0_545.23.06_linux.run

# Install
sudo sh cuda_12.3.0_545.23.06_linux.run

# Add to PATH
echo 'export PATH=/usr/local/cuda-12.3/bin:$PATH' >> ~/.bashrc
echo 'export LD_LIBRARY_PATH=/usr/local/cuda-12.3/lib64:$LD_LIBRARY_PATH' >> ~/.bashrc
source ~/.bashrc

# Verify
nvcc --version
nvidia-smi
```

## CUDA Installation (Windows)

1. **Install NVIDIA Drivers**
   - Download from [NVIDIA Driver Downloads](https://www.nvidia.com/Download/index.aspx)
   - Install and reboot

2. **Install CUDA Toolkit**
   ```powershell
   # Download CUDA Toolkit 12.3
   # https://developer.nvidia.com/cuda-downloads
   
   # Run installer (cuda_12.3.0_546.12_windows.exe)
   # Select "Custom" and include:
   #   - CUDA Toolkit
   #   - Visual Studio Integration
   ```

3. **Verify Installation**
   ```powershell
   nvcc --version
   nvidia-smi
   ```

## OpenCL Installation

### Linux (Intel/AMD GPUs)

```bash
# For Intel GPUs
sudo apt install intel-opencl-icd

# For AMD GPUs
sudo apt install rocm-opencl-runtime

# Verify
clinfo
```

### Windows (Intel/AMD GPUs)

- **Intel**: Install [Intel Graphics Driver](https://www.intel.com/content/www/us/en/download-center/home.html)
- **AMD**: Install [AMD Radeon Software](https://www.amd.com/en/support)

Verify with `clinfo` (install from [GitHub](https://github.com/Oblomov/clinfo))

## TornadoVM Installation

### Automated Installation (Linux)

```bash
# Clone TornadoVM
cd ~
git clone https://github.com/beehive-lab/TornadoVM.git
cd TornadoVM

# Install dependencies (Ubuntu/Debian)
sudo apt install cmake maven

# Configure for CUDA
export TORNADO_SDK=$HOME/TornadoVM/bin/sdk
echo "export TORNADO_SDK=$HOME/TornadoVM/bin/sdk" >> ~/.bashrc

# Build with CUDA backend
./scripts/tornadoVMInstaller.sh --jdk jdk-21 --backend=opencl,ptx

# Source environment
source setvars.sh
```

### Automated Installation (Windows)

```powershell
# Clone TornadoVM
cd ~
git clone https://github.com/beehive-lab/TornadoVM.git
cd TornadoVM

# Install Maven (if not installed)
choco install maven

# Build
$env:TORNADO_SDK = "$HOME\TornadoVM\bin\sdk"
.\scripts\tornadoVMInstaller.ps1 -jdk jdk-21 -backend opencl,ptx

# Source environment
.\setvars.ps1
```

### Manual TornadoVM Configuration

If using the provided JAR files in `trnd-jar/`:

1. **Verify JARs Present**
   ```bash
   ls trnd-jar/tornado-*.jar
   ```

2. **Set Environment Variables**
   ```bash
   export TORNADO_SDK=/path/to/Datacomp/trnd-jar
   export LD_LIBRARY_PATH=$TORNADO_SDK:$LD_LIBRARY_PATH
   ```

3. **Test TornadoVM**
   ```bash
   cd Datacomp
   ./gradlew test --tests "*GpuFrequencyServiceTest"
   ```

## Building DataComp

```bash
# Clone repository
git clone https://github.com/yourusername/datacomp.git
cd datacomp

# Build project
./gradlew clean build

# Run tests
./gradlew test

# Create distribution
./gradlew installDist
```

## Verification

### Test GPU Availability

```bash
# Run GPU test
./gradlew testGpu

# Or manually check
java -cp "app/build/libs/*:trnd-jar/*" \
    -Dtornado.load.api.implementation=uk.ac.manchester.tornado.runtime.TornadoVMProvider \
    com.datacomp.service.gpu.GpuFrequencyService
```

### Test Compression

```bash
# Generate test file
./gradlew generateLargeTestFile -PsizeMB=100

# Run benchmark
./gradlew runBenchmark -PtestFile=test-data-100mb.bin
```

### Launch UI

```bash
./gradlew run
```

## Troubleshooting

### GPU Not Detected

**Issue**: `GpuFrequencyService` reports GPU unavailable

**Solutions**:
1. Verify CUDA/OpenCL installation:
   ```bash
   nvidia-smi        # For CUDA
   clinfo            # For OpenCL
   ```

2. Check TornadoVM environment:
   ```bash
   echo $TORNADO_SDK
   ls $TORNADO_SDK/tornado-*.jar
   ```

3. Verify GPU drivers:
   ```bash
   # NVIDIA
   nvidia-smi
   
   # AMD
   rocm-smi
   ```

### Build Errors

**Issue**: Gradle build fails

**Solutions**:
1. Clean build:
   ```bash
   ./gradlew clean build --refresh-dependencies
   ```

2. Verify Java version:
   ```bash
   java -version  # Should be 21+
   ```

3. Check Gradle daemon:
   ```bash
   ./gradlew --stop
   ./gradlew build
   ```

### Runtime Errors

**Issue**: Application crashes on startup

**Solutions**:
1. Increase heap size:
   ```bash
   export GRADLE_OPTS="-Xmx4g"
   ./gradlew run
   ```

2. Force CPU mode:
   Edit `app/src/main/resources/application.conf`:
   ```hocon
   gpu.force-cpu = true
   ```

3. Check logs:
   ```bash
   cat logs/datacomp.log
   ```

## Platform-Specific Notes

### Linux Notes

- **SELinux**: May need to disable or configure for GPU access
- **Permissions**: Add user to `video` group for GPU access:
  ```bash
  sudo usermod -a -G video $USER
  ```

### Windows Notes

- **Visual Studio**: Required for CUDA development (Community Edition is free)
- **PATH**: Ensure CUDA bin directory in system PATH
- **PowerShell**: Run as Administrator for driver installation

### macOS Notes

- **Apple Silicon**: TornadoVM support limited
- **Intel Macs**: OpenCL only (no CUDA)
- **Alternative**: Use CPU mode

## Next Steps

- Read [README.md](README.md) for usage guide
- Review [GPU_TUNING.md](docs/GPU_TUNING.md) for performance optimization
- Check [BENCHMARKING.md](docs/BENCHMARKING.md) for benchmark guidelines

## Support

For installation issues:
- Check [GitHub Issues](https://github.com/yourusername/datacomp/issues)
- TornadoVM docs: https://tornadovm.readthedocs.io/
- Email: support@datacomp.io

