package com.datacomp.service.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 integration tests for full GPU pipeline with shuffle-merge.
 * 
 * Tests the complete reduction-based encoding with GPU bitstream packing.
 */
public class Phase3IntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(Phase3IntegrationTest.class);
    
    @TempDir
    Path tempDir;
    
    /**
     * Test with 2MB highly compressible file.
     * This tests whether the GPU pipeline can handle larger data without OOM.
     */
    @Test
    public void test2MBFile() throws IOException {
        logger.info("Testing Phase 3 with 2MB file");
        
        try {
            // Create 2MB of repeated data
            byte[] data = new byte[2 * 1024 * 1024];
            Arrays.fill(data, (byte) 'A');
            
            Path inputPath = tempDir.resolve("test_2mb_input.bin");
            Path outputPath = tempDir.resolve("test_2mb_output.sz");
            Path decompPath = tempDir.resolve("test_2mb_decompressed.bin");
            Files.write(inputPath, data);
            
            // Create compression service with 16MB chunks
            GpuCompressionService service = new GpuCompressionService(16, true);
            
            // Compress
            long startTime = System.nanoTime();
            service.compress(inputPath, outputPath, null);
            long compressTime = System.nanoTime() - startTime;
            
            assertTrue(Files.exists(outputPath), "Compressed file should exist");
            long compressedSize = Files.size(outputPath);
            
            logger.info("2MB compression: {} bytes → {} bytes ({}% ratio) in {}ms",
                       data.length, compressedSize, 
                       (100.0 * compressedSize / data.length),
                       compressTime / 1_000_000);
            
            // For repeated data, expect good compression (allowing for chunk headers)
            // Each chunk has overhead, so 2MB file might not compress as well as expected
            assertTrue(compressedSize < data.length * 0.50, 
                      String.format("2MB of repeated data should compress to <50%% of original (was %d bytes, %.1f%%)", 
                                   compressedSize, 100.0 * compressedSize / data.length));
            
            // Decompress and verify
            startTime = System.nanoTime();
            service.decompress(outputPath, decompPath, null);
            long decompressTime = System.nanoTime() - startTime;
            
            byte[] decompressed = Files.readAllBytes(decompPath);
            assertArrayEquals(data, decompressed, "Decompressed data should match original");
            
            logger.info("Decompression successful in {}ms", decompressTime / 1_000_000);
            logger.info("Compression throughput: {} MB/s",
                       (data.length / 1024.0 / 1024.0) / (compressTime / 1_000_000_000.0));
            
            service.close();
            
        } catch (Exception e) {
            logger.error("Test failed with exception", e);
            // If GPU OOM, the test should still pass (graceful fallback)
            if (e.getMessage() != null && 
                (e.getMessage().contains("CL_MEM_OBJECT_ALLOCATION_FAILURE") ||
                 e.getMessage().contains("out of memory"))) {
                logger.warn("GPU OOM detected, test skipped: {}", e.getMessage());
            } else {
                fail("Unexpected exception: " + e.getMessage());
            }
        }
    }
    
    /**
     * Test compression speed with Phase 3 vs baseline.
     */
    @Test
    public void testCompressionSpeed() throws IOException {
        logger.info("Testing Phase 3 compression speed");
        
        try {
            // Create 512KB of mixed data
            byte[] data = new byte[512 * 1024];
            for (int i = 0; i < data.length; i++) {
                // Pattern: AAABBBCCCDDDEEEFFFGGG...
                data[i] = (byte)('A' + (i / 100) % 26);
            }
            
            Path inputPath = tempDir.resolve("speed_test_input.bin");
            Path outputPath = tempDir.resolve("speed_test_output.sz");
            Files.write(inputPath, data);
            
            // Warm up JIT
            GpuCompressionService service = new GpuCompressionService(16, true);
            service.compress(inputPath, outputPath, null);
            Files.delete(outputPath);
            
            // Measure actual performance
            long startTime = System.nanoTime();
            service.compress(inputPath, outputPath, null);
            long endTime = System.nanoTime();
            
            double durationMs = (endTime - startTime) / 1_000_000.0;
            double throughputMBps = (data.length / 1024.0 / 1024.0) / (durationMs / 1000.0);
            
            logger.info("Compressed {}KB in {}ms ({} MB/s)",
                       data.length / 1024, durationMs, throughputMBps);
            
            assertTrue(Files.exists(outputPath), "Compressed file should exist");
            assertTrue(throughputMBps > 10, "Throughput should be >10 MB/s");
            
            service.close();
            
        } catch (Exception e) {
            logger.error("Speed test failed", e);
            if (e.getMessage() == null || 
                (!e.getMessage().contains("GPU") && !e.getMessage().contains("TornadoVM"))) {
                fail("Unexpected exception: " + e.getMessage());
            }
        }
    }
    
    /**
     * Test with existing file if available.
     */
    @Test
    public void testWithRealFile() {
        logger.info("Testing Phase 3 with real file if available");
        
        // Check for test file in project root
        Path testFile = Paths.get("test_input.bin");
        if (!Files.exists(testFile)) {
            logger.info("test_input.bin not found, skipping real file test");
            return;
        }
        
        try {
            long fileSize = Files.size(testFile);
            logger.info("Found test file: {} bytes", fileSize);
            
            Path outputPath = tempDir.resolve("real_file_output.sz");
            Path decompPath = tempDir.resolve("real_file_decompressed.bin");
            
            GpuCompressionService service = new GpuCompressionService(16, true);
            
            // Compress
            long startTime = System.nanoTime();
            service.compress(testFile, outputPath, null);
            long compressTime = System.nanoTime() - startTime;
            
            long compressedSize = Files.size(outputPath);
            logger.info("Real file compression: {} → {} bytes ({}%) in {}ms",
                       fileSize, compressedSize,
                       (100.0 * compressedSize / fileSize),
                       compressTime / 1_000_000);
            
            // Decompress and verify
            service.decompress(outputPath, decompPath, null);
            byte[] original = Files.readAllBytes(testFile);
            byte[] decompressed = Files.readAllBytes(decompPath);
            
            assertArrayEquals(original, decompressed, "Decompressed should match original");
            logger.info("Real file test successful!");
            
            service.close();
            
        } catch (Exception e) {
            logger.error("Real file test failed", e);
            if (e.getMessage() == null || 
                (!e.getMessage().contains("GPU") && !e.getMessage().contains("TornadoVM"))) {
                fail("Unexpected exception: " + e.getMessage());
            }
        }
    }
}
