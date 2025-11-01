package com.datacomp.service;

import com.datacomp.model.CompressionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service for managing compression/decompression operation metrics.
 * Thread-safe singleton for sharing metrics across UI components.
 */
public class MetricsService {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);
    private static final int MAX_METRICS = 50; // Keep last 50 operations
    
    private static MetricsService instance;
    
    private final List<CompressionMetrics> metrics;
    private final List<MetricsListener> listeners;
    
    private MetricsService() {
        this.metrics = new CopyOnWriteArrayList<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }
    
    /**
     * Get singleton instance.
     */
    public static synchronized MetricsService getInstance() {
        if (instance == null) {
            instance = new MetricsService();
        }
        return instance;
    }
    
    /**
     * Add new metrics entry.
     */
    public void addMetrics(CompressionMetrics metric) {
        logger.debug("Adding metrics: {}", metric);
        
        metrics.add(0, metric); // Add to front (most recent first)
        
        // Trim if exceeds max
        while (metrics.size() > MAX_METRICS) {
            metrics.remove(metrics.size() - 1);
        }
        
        // Notify listeners
        notifyListeners();
    }
    
    /**
     * Get all metrics (most recent first).
     */
    public List<CompressionMetrics> getAllMetrics() {
        return new ArrayList<>(metrics);
    }
    
    /**
     * Get most recent N metrics.
     */
    public List<CompressionMetrics> getRecentMetrics(int count) {
        int size = Math.min(count, metrics.size());
        return new ArrayList<>(metrics.subList(0, size));
    }
    
    /**
     * Get latest metric, or null if none.
     */
    public CompressionMetrics getLatestMetric() {
        return metrics.isEmpty() ? null : metrics.get(0);
    }
    
    /**
     * Clear all metrics.
     */
    public void clearMetrics() {
        logger.debug("Clearing all metrics");
        metrics.clear();
        notifyListeners();
    }
    
    /**
     * Add listener for metrics updates.
     */
    public void addListener(MetricsListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Remove listener.
     */
    public void removeListener(MetricsListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Notify all listeners of metrics change.
     */
    private void notifyListeners() {
        for (MetricsListener listener : listeners) {
            try {
                listener.onMetricsUpdated();
            } catch (Exception e) {
                logger.error("Error notifying metrics listener", e);
            }
        }
    }
    
    /**
     * Calculate average throughput across all operations.
     */
    public double getAverageThroughput() {
        if (metrics.isEmpty()) return 0;
        return metrics.stream()
                .mapToDouble(CompressionMetrics::getThroughputMBps)
                .average()
                .orElse(0);
    }
    
    /**
     * Calculate average throughput for specific operation type.
     */
    public double getAverageThroughput(CompressionMetrics.OperationType type) {
        return metrics.stream()
                .filter(m -> m.getOperationType() == type)
                .mapToDouble(CompressionMetrics::getThroughputMBps)
                .average()
                .orElse(0);
    }
    
    /**
     * Get metrics for specific processor type (CPU/GPU).
     */
    public List<CompressionMetrics> getMetricsByProcessor(String processorType) {
        return metrics.stream()
                .filter(m -> m.getProcessorType().equalsIgnoreCase(processorType))
                .toList();
    }
    
    /**
     * Listener interface for metrics updates.
     */
    public interface MetricsListener {
        void onMetricsUpdated();
    }
}
