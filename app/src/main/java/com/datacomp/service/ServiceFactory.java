package com.datacomp.service;

import com.datacomp.config.AppConfig;
import com.datacomp.service.cpu.CpuCompressionService;
import com.datacomp.service.cpu.CpuFrequencyService;
import com.datacomp.service.gpu.GpuCompressionService;
import com.datacomp.service.gpu.GpuFrequencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating compression services based on configuration.
 */
public class ServiceFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceFactory.class);
    
    /**
     * Create compression service based on configuration.
     */
    public static CompressionService createCompressionService(AppConfig config) {
        int chunkSizeMB = config.getChunkSizeMB();
        
        if (config.isForceCpu()) {
            logger.info("CPU mode forced by configuration");
            return new CpuCompressionService(chunkSizeMB);
        }
        
        if (config.isGpuAutoDetect()) {
            try {
                GpuCompressionService gpuService = new GpuCompressionService(
                    chunkSizeMB, config.isGpuFallbackOnError());
                
                if (gpuService.isAvailable()) {
                    logger.info("Using GPU compression service");
                    return gpuService;
                } else {
                    logger.info("GPU not available, using CPU service");
                    return new CpuCompressionService(chunkSizeMB);
                }
            } catch (Exception e) {
                logger.warn("Failed to create GPU service, using CPU", e);
                return new CpuCompressionService(chunkSizeMB);
            }
        }
        
        return new CpuCompressionService(chunkSizeMB);
    }
    
    /**
     * Create frequency service based on configuration.
     */
    public static FrequencyService createFrequencyService(AppConfig config) {
        if (config.isForceCpu()) {
            return new CpuFrequencyService();
        }
        
        if (config.isGpuAutoDetect()) {
            try {
                GpuFrequencyService gpuService = new GpuFrequencyService();
                if (gpuService.isAvailable()) {
                    return gpuService;
                }
            } catch (Exception e) {
                logger.warn("GPU frequency service unavailable", e);
            }
        }
        
        return new CpuFrequencyService();
    }
}

