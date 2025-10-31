package com.datacomp.benchmark;

import com.datacomp.config.AppConfig;
import com.datacomp.service.CompressionService;
import com.datacomp.service.cpu.CpuCompressionService;
import com.datacomp.service.gpu.GpuCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Benchmark suite for comparing CPU vs GPU compression performance.
 */
public class BenchmarkSuite {
    
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkSuite.class);
    
    private final AppConfig config;
    private final int warmupIterations;
    private final int measurementIterations;
    
    public BenchmarkSuite(AppConfig config) {
        this.config = config;
        this.warmupIterations = config.getWarmupIterations();
        this.measurementIterations = config.getMeasurementIterations();
    }
    
    /**
     * Run full benchmark suite comparing CPU and GPU.
     */
    public BenchmarkComparison runFullSuite(Path testFile) throws IOException {
        logger.info("Starting benchmark suite on file: {}", testFile);
        
        List<BenchmarkResult> results = new ArrayList<>();
        
        // CPU benchmark
        CompressionService cpuService = new CpuCompressionService(config.getChunkSizeMB());
        BenchmarkResult cpuResult = benchmarkService(cpuService, testFile, "CPU");
        results.add(cpuResult);
        
        // GPU benchmark (if available)
        try {
            CompressionService gpuService = new GpuCompressionService(
                config.getChunkSizeMB(), false);
            
            if (gpuService.isAvailable()) {
                BenchmarkResult gpuResult = benchmarkService(gpuService, testFile, "GPU");
                results.add(gpuResult);
            } else {
                logger.info("GPU not available, skipping GPU benchmark");
            }
        } catch (Exception e) {
            logger.warn("Failed to benchmark GPU service", e);
        }
        
        return new BenchmarkComparison(results);
    }
    
    /**
     * Benchmark a specific service.
     */
    public BenchmarkResult benchmarkService(CompressionService service,
                                           Path testFile,
                                           String benchmarkName) throws IOException {
        logger.info("Benchmarking {}: {}", benchmarkName, service.getServiceName());
        
        Path outputFile = Files.createTempFile("benchmark_", ".dcz");
        
        try {
            // Warmup
            for (int i = 0; i < warmupIterations; i++) {
                logger.debug("Warmup iteration {}/{}", i + 1, warmupIterations);
                service.compress(testFile, outputFile, null);
                Files.delete(outputFile);
            }
            
            // Measurement
            long totalDuration = 0;
            long inputSize = Files.size(testFile);
            long outputSize = 0;
            
            for (int i = 0; i < measurementIterations; i++) {
                logger.debug("Measurement iteration {}/{}", i + 1, measurementIterations);
                
                long startTime = System.nanoTime();
                service.compress(testFile, outputFile, null);
                long duration = System.nanoTime() - startTime;
                
                totalDuration += duration;
                outputSize = Files.size(outputFile);
                
                Files.delete(outputFile);
            }
            
            long avgDuration = totalDuration / measurementIterations;
            
            BenchmarkResult result = new BenchmarkResult.Builder()
                .benchmarkName(benchmarkName)
                .serviceName(service.getServiceName())
                .inputSize(inputSize)
                .outputSize(outputSize)
                .totalDuration(avgDuration)
                .iterations(measurementIterations)
                .build();
            
            logger.info("Benchmark complete: {}", result);
            
            return result;
            
        } finally {
            if (Files.exists(outputFile)) {
                Files.delete(outputFile);
            }
        }
    }
    
    /**
     * Comparison of benchmark results.
     */
    public static class BenchmarkComparison {
        private final List<BenchmarkResult> results;
        
        public BenchmarkComparison(List<BenchmarkResult> results) {
            this.results = new ArrayList<>(results);
        }
        
        public List<BenchmarkResult> getResults() {
            return new ArrayList<>(results);
        }
        
        public BenchmarkResult getFastest() {
            return results.stream()
                .max((a, b) -> Double.compare(a.getThroughputMBps(), b.getThroughputMBps()))
                .orElse(null);
        }
        
        public double getSpeedup() {
            if (results.size() < 2) return 1.0;
            
            BenchmarkResult cpu = results.get(0);
            BenchmarkResult gpu = results.get(1);
            
            return gpu.getThroughputMBps() / cpu.getThroughputMBps();
        }
        
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Benchmark Results ===\n");
            
            for (BenchmarkResult result : results) {
                sb.append(String.format("%s: %.2f MB/s (%.3fs)\n",
                    result.getServiceName(),
                    result.getThroughputMBps(),
                    result.getDurationSeconds()));
            }
            
            if (results.size() >= 2) {
                sb.append(String.format("GPU Speedup: %.2fx\n", getSpeedup()));
            }
            
            return sb.toString();
        }
    }
}

