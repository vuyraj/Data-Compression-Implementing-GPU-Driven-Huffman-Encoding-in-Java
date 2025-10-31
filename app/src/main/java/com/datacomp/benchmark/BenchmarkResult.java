package com.datacomp.benchmark;

import java.util.HashMap;
import java.util.Map;

/**
 * Result of a benchmark run.
 */
public class BenchmarkResult {
    
    private final String benchmarkName;
    private final String serviceName;
    private final long inputSizeBytes;
    private final long outputSizeBytes;
    private final Map<String, Long> stageDurations; // Stage name -> duration in nanoseconds
    private final long totalDuration;
    private final int iterations;
    
    private BenchmarkResult(Builder builder) {
        this.benchmarkName = builder.benchmarkName;
        this.serviceName = builder.serviceName;
        this.inputSizeBytes = builder.inputSizeBytes;
        this.outputSizeBytes = builder.outputSizeBytes;
        this.stageDurations = new HashMap<>(builder.stageDurations);
        this.totalDuration = builder.totalDuration;
        this.iterations = builder.iterations;
    }
    
    public String getBenchmarkName() { return benchmarkName; }
    public String getServiceName() { return serviceName; }
    public long getInputSizeBytes() { return inputSizeBytes; }
    public long getOutputSizeBytes() { return outputSizeBytes; }
    public Map<String, Long> getStageDurations() { return new HashMap<>(stageDurations); }
    public long getTotalDuration() { return totalDuration; }
    public int getIterations() { return iterations; }
    
    public double getThroughputMBps() {
        if (totalDuration == 0) return 0;
        return (inputSizeBytes / 1_000_000.0) / (totalDuration / 1_000_000_000.0);
    }
    
    public double getCompressionRatio() {
        if (inputSizeBytes == 0) return 1.0;
        return (double) outputSizeBytes / inputSizeBytes;
    }
    
    public double getDurationSeconds() {
        return totalDuration / 1_000_000_000.0;
    }
    
    public double getStageDurationSeconds(String stage) {
        Long duration = stageDurations.get(stage);
        return (duration != null) ? duration / 1_000_000_000.0 : 0;
    }
    
    @Override
    public String toString() {
        return String.format("%s [%s]: %.2f MB/s, %.2f%% compression, %.3fs total",
            benchmarkName, serviceName, getThroughputMBps(),
            getCompressionRatio() * 100, getDurationSeconds());
    }
    
    public static class Builder {
        private String benchmarkName;
        private String serviceName;
        private long inputSizeBytes;
        private long outputSizeBytes;
        private Map<String, Long> stageDurations = new HashMap<>();
        private long totalDuration;
        private int iterations = 1;
        
        public Builder benchmarkName(String name) {
            this.benchmarkName = name;
            return this;
        }
        
        public Builder serviceName(String name) {
            this.serviceName = name;
            return this;
        }
        
        public Builder inputSize(long bytes) {
            this.inputSizeBytes = bytes;
            return this;
        }
        
        public Builder outputSize(long bytes) {
            this.outputSizeBytes = bytes;
            return this;
        }
        
        public Builder stageDuration(String stage, long nanos) {
            this.stageDurations.put(stage, nanos);
            return this;
        }
        
        public Builder totalDuration(long nanos) {
            this.totalDuration = nanos;
            return this;
        }
        
        public Builder iterations(int count) {
            this.iterations = count;
            return this;
        }
        
        public BenchmarkResult build() {
            return new BenchmarkResult(this);
        }
    }
}

