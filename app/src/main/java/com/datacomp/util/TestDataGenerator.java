package com.datacomp.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Utility to generate test data files for benchmarking.
 */
public class TestDataGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(TestDataGenerator.class);
    
    /**
     * Generate a test file with random data.
     * 
     * @param sizeMB Size in megabytes
     * @param outputPath Output file path
     */
    public static void generateRandomFile(int sizeMB, Path outputPath) throws IOException {
        logger.info("Generating {}MB random test file: {}", sizeMB, outputPath);
        
        long sizeBytes = sizeMB * 1024L * 1024L;
        Random random = new Random(42); // Deterministic seed
        
        byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
        
        try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath))) {
            long remaining = sizeBytes;
            
            while (remaining > 0) {
                int toWrite = (int) Math.min(buffer.length, remaining);
                random.nextBytes(buffer);
                out.write(buffer, 0, toWrite);
                remaining -= toWrite;
                
                if (remaining % (100 * 1024 * 1024) == 0) {
                    logger.debug("Generated {} MB...", (sizeBytes - remaining) / (1024 * 1024));
                }
            }
        }
        
        logger.info("Test file generated: {} bytes", sizeBytes);
    }
    
    /**
     * Generate a text file with repeating patterns (highly compressible).
     */
    public static void generateCompressibleFile(int sizeMB, Path outputPath) throws IOException {
        logger.info("Generating {}MB compressible test file: {}", sizeMB, outputPath);
        
        long sizeBytes = sizeMB * 1024L * 1024L;
        String pattern = "The quick brown fox jumps over the lazy dog. ";
        byte[] patternBytes = pattern.getBytes();
        
        try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath))) {
            long written = 0;
            
            while (written < sizeBytes) {
                int toWrite = (int) Math.min(patternBytes.length, sizeBytes - written);
                out.write(patternBytes, 0, toWrite);
                written += toWrite;
            }
        }
        
        logger.info("Compressible test file generated: {} bytes", sizeBytes);
    }
    
    /**
     * CLI entry point.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: TestDataGenerator <sizeMB> [outputFile] [--compressible]");
            System.exit(1);
        }
        
        try {
            int sizeMB = Integer.parseInt(args[0]);
            String outputFile = args.length > 1 ? args[1] : "test-data-" + sizeMB + "mb.bin";
            boolean compressible = args.length > 2 && args[2].equals("--compressible");
            
            Path outputPath = Paths.get(outputFile);
            
            if (compressible) {
                generateCompressibleFile(sizeMB, outputPath);
            } else {
                generateRandomFile(sizeMB, outputPath);
            }
            
            System.out.println("Generated: " + outputPath.toAbsolutePath());
            
        } catch (Exception e) {
            logger.error("Failed to generate test file", e);
            System.exit(1);
        }
    }
}

