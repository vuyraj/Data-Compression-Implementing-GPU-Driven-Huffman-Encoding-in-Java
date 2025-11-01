# DataComp CLI Usage Guide

## Command-Line Interface for Compression/Decompression

The DataComp CLI tool allows you to compress and decompress files from the command line with target/destination paths.

## Usage

### Compress a file
```bash
./gradlew :app:compress -Pinput=<source-file> -Poutput=<destination-file> [-Pchunk=<size-MB>]
```

### Decompress a file
```bash
./gradlew :app:decompress -Pinput=<compressed-file> -Poutput=<destination-file>
```

### Run custom CLI commands
```bash
./gradlew :app:cli -Pargs="<command> <args>"
```

## Examples

### Example 1: Compress a file
```bash
# Compress input.tar to compressed/output.dc using default 512MB chunks (optimized for GPU)
./gradlew :app:compress -Pinput=/home/vuyraj/input.tar -Poutput=/tmp/output.dc

# Compress with custom 256MB chunks
./gradlew :app:compress -Pinput=/home/vuyraj/input.tar -Poutput=/tmp/output.dc -Pchunk=256

# Compress with 1GB chunks for maximum GPU performance
./gradlew :app:compress -Pinput=/home/vuyraj/input.tar -Poutput=/tmp/output.dc -Pchunk=1024
```

### Example 2: Decompress a file
```bash
# Decompress output.dc to restored.tar
./gradlew :app:decompress -Pinput=/tmp/output.dc -Poutput=/home/vuyraj/restored.tar
```

### Example 3: Using the generic CLI task
```bash
# Show help
./gradlew :app:cli

# Compress (short form) with 512MB chunks (default)
./gradlew :app:cli -Pargs="c /home/vuyraj/input.tar /tmp/output.dc 512"

# Compress with 1GB chunks for maximum GPU throughput
./gradlew :app:cli -Pargs="c /home/vuyraj/input.tar /tmp/output.dc 1024"

# Decompress (short form)
./gradlew :app:cli -Pargs="d /tmp/output.dc /home/vuyraj/restored.tar"
```

## Features

- **Automatic directory creation**: Output directories are created automatically if they don't exist
- **Progress tracking**: Real-time progress updates during compression/decompression
- **Performance metrics**: Shows compression ratio, throughput (MB/s), and time taken
- **Chunked processing**: Configurable chunk size for memory-efficient processing of large files
- **Integrity verification**: SHA-256 checksums ensure data integrity

## Output Format

### Compression Output
```
Compressing...
  Input:  /home/vuyraj/input.tar
  Output: /tmp/output.dc
  Size:   58.57 MB
Progress: 100%

Compression complete!
  Original size:   58.57 MB
  Compressed size: 48.12 MB
  Compression ratio: 82.16%
  Time: 2.34 seconds
  Throughput: 25.03 MB/s
```

### Decompression Output
```
Decompressing...
  Input:  /tmp/output.dc
  Output: /home/vuyraj/restored.tar
Progress: 100%

Decompression complete!
  Compressed size:   48.12 MB
  Decompressed size: 58.57 MB
  Time: 1.89 seconds
  Throughput: 30.98 MB/s
```

## Advanced Usage

### Running without Gradle
You can also run the CLI directly with Java (after building):

```bash
# Build the project first
./gradlew :app:build

# Run directly
java -cp "app/build/libs/*:app/build/classes/java/main" \
  com.datacomp.cli.DataCompCLI compress input.tar output.dc 8
```

### Integration with Scripts
```bash
#!/bin/bash
INPUT_DIR="/data/archives"
OUTPUT_DIR="/backup/compressed"

for file in "$INPUT_DIR"/*.tar; do
    filename=$(basename "$file")
    ./gradlew :app:compress \
        -Pinput="$file" \
        -Poutput="$OUTPUT_DIR/${filename}.dc" \
        -Pchunk=16
done
```

## Troubleshooting

### "Input file does not exist"
- Verify the input file path is correct
- Use absolute paths to avoid confusion

### "Permission denied" on output
- Check write permissions on the output directory
- The tool will create directories but needs write access

### Out of memory errors
- Reduce chunk size: `-Pchunk=2` (default is 4MB)
- Increase JVM heap: Add `-Xmx8g` to Gradle JVM args

## File Format

Compressed files use the `.dc` (DataComp) extension and include:
- Compression header with metadata
- Per-chunk canonical Huffman codes
- SHA-256 checksums for integrity verification
- Original filename and timestamp preservation
