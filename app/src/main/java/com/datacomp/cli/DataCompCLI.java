package com.datacomp.cli;

import com.datacomp.service.CompressionService;
import com.datacomp.service.cpu.CpuCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line interface for DataComp compression/decompression.
 * 
 * Usage:
 *   Compress:   java -jar datacomp.jar compress <input-file> <output-file>
 *   Decompress: java -jar datacomp.jar decompress <input-file> <output-file>
 */
public class DataCompCLI {
    
    private static final Logger logger = LoggerFactory.getLogger(DataCompCLI.class);
    
    public static void main(String[] args) {
        if (args.length < 3) {
            printUsage();
            System.exit(1);
        }
        
        String operation = args[0].toLowerCase();
        String inputPath = args[1];
        String outputPath = args[2];
        
        // Optional: chunk size in MB (default: 512 for optimal GPU performance)
        int chunkSizeMB = 512;
        if (args.length > 3) {
            try {
                chunkSizeMB = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid chunk size: " + args[3]);
                System.exit(1);
            }
        }
        
        try {
            Path input = Paths.get(inputPath);
            Path output = Paths.get(outputPath);
            
            // Validate input exists
            if (!Files.exists(input)) {
                System.err.println("Error: Input file does not exist: " + inputPath);
                System.exit(1);
            }
            
            // Create output directory if needed
            Path outputDir = output.getParent();
            if (outputDir != null && !Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
                logger.info("Created output directory: {}", outputDir);
            }
            
            CompressionService service = new CpuCompressionService(chunkSizeMB);
            
            switch (operation) {
                case "compress":
                case "c":
                    compress(service, input, output);
                    break;
                    
                case "decompress":
                case "d":
                    decompress(service, input, output);
                    break;
                    
                default:
                    System.err.println("Unknown operation: " + operation);
                    printUsage();
                    System.exit(1);
            }
            
        } catch (IOException e) {
            logger.error("Operation failed", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void compress(CompressionService service, Path input, Path output) 
            throws IOException {
        long startTime = System.currentTimeMillis();
        long inputSize = Files.size(input);
        
        System.out.println("Compressing...");
        System.out.println("  Input:  " + input);
        System.out.println("  Output: " + output);
        System.out.println("  Size:   " + formatSize(inputSize));
        
        service.compress(input, output, progress -> {
            int percent = (int) (progress * 100);
            System.out.print("\rProgress: " + percent + "%");
        });
        
        long endTime = System.currentTimeMillis();
        long outputSize = Files.size(output);
        double ratio = (double) outputSize / inputSize;
        double timeSec = (endTime - startTime) / 1000.0;
        double throughputMBps = (inputSize / 1_000_000.0) / timeSec;
        
        System.out.println("\n\nCompression complete!");
        System.out.println("  Original size:   " + formatSize(inputSize));
        System.out.println("  Compressed size: " + formatSize(outputSize));
        System.out.println("  Compression ratio: " + String.format("%.2f%%", ratio * 100));
        System.out.println("  Time: " + String.format("%.2f", timeSec) + " seconds");
        System.out.println("  Throughput: " + String.format("%.2f", throughputMBps) + " MB/s");
    }
    
    private static void decompress(CompressionService service, Path input, Path output) 
            throws IOException {
        long startTime = System.currentTimeMillis();
        long inputSize = Files.size(input);
        
        System.out.println("Decompressing...");
        System.out.println("  Input:  " + input);
        System.out.println("  Output: " + output);
        
        service.decompress(input, output, progress -> {
            int percent = (int) (progress * 100);
            System.out.print("\rProgress: " + percent + "%");
        });
        
        long endTime = System.currentTimeMillis();
        long outputSize = Files.size(output);
        double timeSec = (endTime - startTime) / 1000.0;
        double throughputMBps = (outputSize / 1_000_000.0) / timeSec;
        
        System.out.println("\n\nDecompression complete!");
        System.out.println("  Compressed size:   " + formatSize(inputSize));
        System.out.println("  Decompressed size: " + formatSize(outputSize));
        System.out.println("  Time: " + String.format("%.2f", timeSec) + " seconds");
        System.out.println("  Throughput: " + String.format("%.2f", throughputMBps) + " MB/s");
    }
    
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private static void printUsage() {
        System.out.println("DataComp - GPU-Accelerated Compression Tool");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  Compress:   java -jar datacomp.jar compress <input-file> <output-file> [chunk-size-MB]");
        System.out.println("  Decompress: java -jar datacomp.jar decompress <input-file> <output-file>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar datacomp.jar compress data.tar data.tar.dc");
        System.out.println("  java -jar datacomp.jar compress large-file.bin /tmp/compressed.dc 8");
        System.out.println("  java -jar datacomp.jar decompress data.tar.dc data-restored.tar");
        System.out.println();
        System.out.println("Short forms:");
        System.out.println("  'c' for compress, 'd' for decompress");
    }
}
