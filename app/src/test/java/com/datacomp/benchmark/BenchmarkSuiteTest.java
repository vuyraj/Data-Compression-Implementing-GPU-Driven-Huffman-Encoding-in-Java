package com.datacomp.benchmark;

import com.datacomp.config.AppConfig;
import com.datacomp.util.TestDataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

/**
 * Tests for benchmark suite.
 */
class BenchmarkSuiteTest {
    
    @TempDir
    Path tempDir;
    
    private BenchmarkSuite suite;
    private AppConfig config;
    
    @BeforeEach
    void setUp() {
        config = new AppConfig();
        suite = new BenchmarkSuite(config);
    }
    
    @Test
    void testBenchmarkWithSmallFile() throws Exception {
        // Generate small test file
        Path testFile = tempDir.resolve("test.bin");
        TestDataGenerator.generateRandomFile(1, testFile); // 1MB
        
        BenchmarkSuite.BenchmarkComparison comparison = suite.runFullSuite(testFile);
        
        assertNotNull(comparison);
        assertFalse(comparison.getResults().isEmpty());
        
        // Should have at least CPU result
        assertTrue(comparison.getResults().size() >= 1);
        
        BenchmarkResult result = comparison.getResults().get(0);
        assertNotNull(result);
        assertTrue(result.getThroughputMBps() > 0);
        assertTrue(result.getDurationSeconds() > 0);
    }
}

