package com.datacomp.service.cpu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Integration tests for CPU compression service.
 */
class CpuCompressionServiceTest {
    
    @TempDir
    Path tempDir;
    
    private CpuCompressionService service;
    
    @BeforeEach
    void setUp() {
        service = new CpuCompressionService(1); // 1MB chunks for testing
    }
    
    @Test
    void testServiceAvailable() {
        assertTrue(service.isAvailable());
        assertEquals("CPU Compression", service.getServiceName());
    }
    
    @Test
    void testCompressDecompressSmallFile() throws IOException {
        // Create test file
        Path inputFile = tempDir.resolve("test.txt");
        String content = "Hello World! ".repeat(100);
        Files.writeString(inputFile, content);
        
        Path compressedFile = tempDir.resolve("test.dcz");
        Path decompressedFile = tempDir.resolve("test.decompressed.txt");
        
        // Compress
        service.compress(inputFile, compressedFile, null);
        assertTrue(Files.exists(compressedFile));
        assertTrue(Files.size(compressedFile) > 0);
        
        // Decompress
        service.decompress(compressedFile, decompressedFile, null);
        assertTrue(Files.exists(decompressedFile));
        
        // Verify content
        String decompressed = Files.readString(decompressedFile);
        assertEquals(content, decompressed);
    }
    
    @Test
    void testCompressDecompressRandomData() throws IOException {
        // Create random data file
        Path inputFile = tempDir.resolve("random.bin");
        byte[] data = new byte[10 * 1024]; // 10KB
        new Random(42).nextBytes(data);
        Files.write(inputFile, data);
        
        Path compressedFile = tempDir.resolve("random.dcz");
        Path decompressedFile = tempDir.resolve("random.decompressed.bin");
        
        // Compress
        service.compress(inputFile, compressedFile, null);
        
        // Decompress
        service.decompress(compressedFile, decompressedFile, null);
        
        // Verify binary content
        byte[] decompressed = Files.readAllBytes(decompressedFile);
        assertArrayEquals(data, decompressed);
    }
    
    @Test
    void testCompressDecompressEmptyFile() throws IOException {
        Path inputFile = tempDir.resolve("empty.txt");
        Files.createFile(inputFile);
        
        Path compressedFile = tempDir.resolve("empty.dcz");
        Path decompressedFile = tempDir.resolve("empty.decompressed.txt");
        
        // Compress
        service.compress(inputFile, compressedFile, null);
        
        // Decompress
        service.decompress(compressedFile, decompressedFile, null);
        
        assertEquals(0, Files.size(decompressedFile));
    }
    
    @Test
    void testCompressDecompressMultiChunk() throws IOException {
        // Create file larger than chunk size
        Path inputFile = tempDir.resolve("large.bin");
        byte[] data = new byte[3 * 1024 * 1024]; // 3MB (3 chunks)
        
        // Fill with pattern
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        Files.write(inputFile, data);
        
        Path compressedFile = tempDir.resolve("large.dcz");
        Path decompressedFile = tempDir.resolve("large.decompressed.bin");
        
        // Compress with progress callback
        boolean[] progressCalled = {false};
        service.compress(inputFile, compressedFile, progress -> {
            progressCalled[0] = true;
            assertTrue(progress >= 0 && progress <= 1.0);
        });
        
        assertTrue(progressCalled[0], "Progress callback should be called");
        
        // Decompress
        service.decompress(compressedFile, decompressedFile, null);
        
        // Verify
        byte[] decompressed = Files.readAllBytes(decompressedFile);
        assertArrayEquals(data, decompressed);
    }
    
    @Test
    void testVerifyIntegrity() throws IOException {
        Path inputFile = tempDir.resolve("test.txt");
        Files.writeString(inputFile, "Test data for integrity check");
        
        Path compressedFile = tempDir.resolve("test.dcz");
        service.compress(inputFile, compressedFile, null);
        
        boolean valid = service.verifyIntegrity(compressedFile);
        assertTrue(valid);
    }
}

