package com.datacomp.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks timing metrics for individual compression/decompression stages.
 */
public class StageMetrics {
    
    public enum Stage {
        FREQUENCY_ANALYSIS("Frequency Analysis"),
        HUFFMAN_TREE_BUILD("Huffman Tree Build"),
        ENCODING("Encoding"),
        CHECKSUM_COMPUTE("Checksum Computation"),
        FILE_IO("File I/O"),
        HEADER_WRITE("Header Write"),
        DECODING("Decoding"),
        CHECKSUM_VERIFY("Checksum Verification");
        
        private final String displayName;
        
        Stage(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    private final Map<Stage, Long> stageTimes; // in nanoseconds
    private final Map<Stage, Integer> stageCounts;
    private final Map<Stage, Long> stageDataSizes; // bytes processed
    
    public StageMetrics() {
        this.stageTimes = new HashMap<>();
        this.stageCounts = new HashMap<>();
        this.stageDataSizes = new HashMap<>();
    }
    
    /**
     * Record time taken for a stage.
     */
    public void recordStage(Stage stage, long nanoTime, long dataSize) {
        stageTimes.merge(stage, nanoTime, Long::sum);
        stageCounts.merge(stage, 1, Integer::sum);
        stageDataSizes.merge(stage, dataSize, Long::sum);
    }
    
    /**
     * Get total time for a stage in milliseconds.
     */
    public double getStageTimeMs(Stage stage) {
        return stageTimes.getOrDefault(stage, 0L) / 1_000_000.0;
    }
    
    /**
     * Get total time for a stage in seconds.
     */
    public double getStageTimeSec(Stage stage) {
        return stageTimes.getOrDefault(stage, 0L) / 1_000_000_000.0;
    }
    
    /**
     * Get number of times a stage was executed.
     */
    public int getStageCount(Stage stage) {
        return stageCounts.getOrDefault(stage, 0);
    }
    
    /**
     * Get average time per execution in milliseconds.
     */
    public double getAverageStageTimeMs(Stage stage) {
        int count = getStageCount(stage);
        if (count == 0) return 0;
        return getStageTimeMs(stage) / count;
    }
    
    /**
     * Get throughput for a stage in MB/s.
     */
    public double getStageThroughputMBps(Stage stage) {
        long dataSize = stageDataSizes.getOrDefault(stage, 0L);
        double timeSec = getStageTimeSec(stage);
        if (timeSec == 0) return 0;
        return (dataSize / 1_000_000.0) / timeSec;
    }
    
    /**
     * Get percentage of total time spent in this stage.
     */
    public double getStagePercentage(Stage stage) {
        long stageTime = stageTimes.getOrDefault(stage, 0L);
        long totalTime = stageTimes.values().stream().mapToLong(Long::longValue).sum();
        if (totalTime == 0) return 0;
        return (stageTime * 100.0) / totalTime;
    }
    
    /**
     * Get all stages that have been recorded.
     */
    public Map<Stage, Long> getAllStageTimes() {
        return new HashMap<>(stageTimes);
    }
    
    /**
     * Get formatted summary of all stages.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Stage Performance Breakdown:\n");
        sb.append("═══════════════════════════════════════════════════\n");
        
        for (Stage stage : Stage.values()) {
            if (stageTimes.containsKey(stage)) {
                sb.append(String.format("%-25s: %8.2f ms (%5.1f%%) [%d runs, avg: %.2f ms]\n",
                    stage.getDisplayName(),
                    getStageTimeMs(stage),
                    getStagePercentage(stage),
                    getStageCount(stage),
                    getAverageStageTimeMs(stage)));
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Reset all metrics.
     */
    public void reset() {
        stageTimes.clear();
        stageCounts.clear();
        stageDataSizes.clear();
    }
}
