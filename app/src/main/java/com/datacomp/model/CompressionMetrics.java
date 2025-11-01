package com.datacomp.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents metrics for a compression or decompression operation.
 */
public class CompressionMetrics {
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    public enum OperationType {
        COMPRESS, DECOMPRESS
    }
    
    private final String fileName;
    private final OperationType operationType;
    private final long inputSizeBytes;
    private final long outputSizeBytes;
    private final double throughputMBps;
    private final double durationSeconds;
    private final LocalDateTime timestamp;
    private final String processorType; // "CPU" or "GPU"
    
    public CompressionMetrics(String fileName, OperationType operationType,
                            long inputSizeBytes, long outputSizeBytes,
                            double throughputMBps, double durationSeconds,
                            String processorType) {
        this.fileName = fileName;
        this.operationType = operationType;
        this.inputSizeBytes = inputSizeBytes;
        this.outputSizeBytes = outputSizeBytes;
        this.throughputMBps = throughputMBps;
        this.durationSeconds = durationSeconds;
        this.processorType = processorType;
        this.timestamp = LocalDateTime.now();
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public OperationType getOperationType() {
        return operationType;
    }
    
    public long getInputSizeBytes() {
        return inputSizeBytes;
    }
    
    public long getOutputSizeBytes() {
        return outputSizeBytes;
    }
    
    public double getThroughputMBps() {
        return throughputMBps;
    }
    
    public double getDurationSeconds() {
        return durationSeconds;
    }
    
    public String getProcessorType() {
        return processorType;
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    /**
     * Get compression ratio (only meaningful for compression operations).
     * Returns output_size / input_size * 100
     */
    public double getCompressionRatio() {
        if (inputSizeBytes == 0) return 0;
        return (double) outputSizeBytes / inputSizeBytes * 100;
    }
    
    /**
     * Get space saved percentage (only meaningful for compression operations).
     */
    public double getSpaceSavedPercent() {
        if (inputSizeBytes == 0) return 0;
        return (1.0 - (double) outputSizeBytes / inputSizeBytes) * 100;
    }
    
    /**
     * Format file size in human-readable format.
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * Get formatted summary for display.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(operationType == OperationType.COMPRESS ? "Compressed" : "Decompressed");
        sb.append(": ").append(fileName);
        sb.append(" (").append(formatSize(inputSizeBytes));
        sb.append(" → ").append(formatSize(outputSizeBytes));
        sb.append(", ").append(String.format("%.2f MB/s", throughputMBps));
        sb.append(", ").append(processorType);
        sb.append(")");
        return sb.toString();
    }
    
    /**
     * Get formatted timestamp.
     */
    public String getFormattedTimestamp() {
        return timestamp.format(FORMATTER);
    }
    
    @Override
    public String toString() {
        return getSummary() + " at " + getFormattedTimestamp();
    }
}
