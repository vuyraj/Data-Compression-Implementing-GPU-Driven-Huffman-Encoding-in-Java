package com.datacomp.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application configuration wrapper.
 */
public class AppConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    private final Config config;
    
    public AppConfig() {
        this(ConfigFactory.load());
    }
    
    public AppConfig(Config config) {
        this.config = config.getConfig("datacomp");
    }
    
    // Compression settings
    public int getChunkSizeMB() {
        return config.getInt("compression.chunk-size-mb");
    }
    
    public int getIoBufferSizeKB() {
        return config.getInt("compression.io-buffer-size-kb");
    }
    
    public int getCpuThreads() {
        int threads = config.getInt("compression.cpu-threads");
        if (threads <= 0) {
            threads = Runtime.getRuntime().availableProcessors();
        }
        return threads;
    }
    
    public boolean useMemoryMappedIo() {
        return config.getBoolean("compression.use-memory-mapped-io");
    }
    
    public int getChunkingThresholdMB() {
        return config.getInt("compression.chunking-threshold-mb");
    }
    
    // GPU settings
    public boolean isGpuAutoDetect() {
        return config.getBoolean("gpu.auto-detect");
    }
    
    public boolean isForceCpu() {
        return config.getBoolean("gpu.force-cpu");
    }
    
    public String getPreferredDevice() {
        return config.getString("gpu.preferred-device");
    }
    
    public int getDeviceIndex() {
        return config.getInt("gpu.device-index");
    }
    
    public int getGpuMemoryLimitMB() {
        return config.getInt("gpu.memory-limit-mb");
    }
    
    public boolean isGpuFallbackOnError() {
        return config.getBoolean("gpu.fallback-on-error");
    }
    
    // Benchmark settings
    public int getWarmupIterations() {
        return config.getInt("benchmark.warmup-iterations");
    }
    
    public int getMeasurementIterations() {
        return config.getInt("benchmark.measurement-iterations");
    }
    
    public boolean isDetailedProfiling() {
        return config.getBoolean("benchmark.detailed-profiling");
    }
    
    public String getBenchmarkOutputFormat() {
        return config.getString("benchmark.output-format");
    }
    
    // Logging settings
    public String getLogLevel() {
        return config.getString("logging.level");
    }
    
    public boolean isMetricsEnabled() {
        return config.getBoolean("logging.metrics-enabled");
    }
    
    public String getLogFilePath() {
        return config.getString("logging.file-path");
    }
    
    // UI settings
    public String getTheme() {
        return config.getString("ui.theme");
    }
    
    public int getWindowWidth() {
        return config.getInt("ui.window.width");
    }
    
    public int getWindowHeight() {
        return config.getInt("ui.window.height");
    }
    
    public boolean isWindowResizable() {
        return config.getBoolean("ui.window.resizable");
    }
    
    public int getProgressUpdateIntervalMs() {
        return config.getInt("ui.progress-update-interval-ms");
    }
    
    public boolean isAnimationsEnabled() {
        return config.getBoolean("ui.animations-enabled");
    }
    
    // Output settings
    public String getDefaultDirectory() {
        return config.getString("output.default-directory");
    }
    
    public String getCompressedExtension() {
        return config.getString("output.compressed-extension");
    }
    
    public boolean isVerifyAfterCompress() {
        return config.getBoolean("output.verify-after-compress");
    }
    
    public boolean isKeepOriginal() {
        return config.getBoolean("output.keep-original");
    }
}

