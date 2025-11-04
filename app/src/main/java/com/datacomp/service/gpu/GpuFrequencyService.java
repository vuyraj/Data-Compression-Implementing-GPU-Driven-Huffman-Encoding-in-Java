package com.datacomp.service.gpu;

import com.datacomp.service.FrequencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.*;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider;

/**
 * GPU-accelerated frequency service using TornadoVM.
 */
public class GpuFrequencyService implements FrequencyService {
    
    private static final Logger logger = LoggerFactory.getLogger(GpuFrequencyService.class);
    
    private final TornadoDevice device;
    
    public GpuFrequencyService() {
        this.device = selectDevice();
    }
    
    public GpuFrequencyService(TornadoDevice device) {
        this.device = device;
    }
    
    private TornadoDevice selectDevice() {
        try {
            // Try to get default device
            TornadoDevice defaultDevice = TornadoRuntimeProvider.getTornadoRuntime()
                .getDefaultDevice();
            
            logger.info("Selected GPU device: {}", defaultDevice.getDescription());
            return defaultDevice;
        } catch (Throwable e) {
            // Catch all errors including ExceptionInInitializerError and NoClassDefFoundError
            logger.warn("Failed to initialize TornadoVM device: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public long[] computeHistogram(byte[] data, int offset, int length) {
        if (!isAvailable()) {
            throw new IllegalStateException("GPU not available");
        }
        
        try {
            logger.debug("🎮 GPU: Computing histogram for {} bytes", length);
            long startTime = System.nanoTime();
            
            // Prepare input (copy relevant portion)
            byte[] input = new byte[length];
            System.arraycopy(data, offset, input, 0, length);
            
            // Allocate output histogram
            int[] histogram = new int[256];
            
            // Create and execute task graph
            TaskGraph taskGraph = new TaskGraph("histogram")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, input)
                .task("compute", TornadoKernels::histogramKernel, input, length, histogram)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, histogram);
            
            ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
            
            try (TornadoExecutionPlan executor = new TornadoExecutionPlan(immutableTaskGraph)) {
                executor.execute();
            }
            
            long duration = System.nanoTime() - startTime;
            double throughputGBps = (length / 1_000_000_000.0) / (duration / 1_000_000_000.0);
            
            logger.debug("🎮 GPU: Histogram completed in {:.2f} ms ({:.2f} GB/s)", 
                       duration / 1_000_000.0, throughputGBps);
            
            // Convert int[] to long[]
            long[] result = new long[256];
            for (int i = 0; i < 256; i++) {
                result[i] = histogram[i] & 0xFFFFFFFFL;
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("GPU histogram computation failed", e);
            throw new RuntimeException("GPU computation failed", e);
        }
    }
    
    @Override
    public String getServiceName() {
        if (device != null) {
            return "GPU (" + device.getDescription() + ")";
        }
        return "GPU (unavailable)";
    }
    
    @Override
    public boolean isAvailable() {
        if (device == null) return false;
        
        try {
            // Test with small array
            byte[] testData = new byte[1024];
            int[] histogram = new int[256];
            
            TaskGraph testGraph = new TaskGraph("test")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, testData)
                .task("test", TornadoKernels::histogramKernel, testData, testData.length, histogram)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, histogram);
            
            ImmutableTaskGraph immutable = testGraph.snapshot();
            
            try (TornadoExecutionPlan executor = new TornadoExecutionPlan(immutable)) {
                executor.execute();
            }
            
            return true;
        } catch (Exception e) {
            logger.warn("GPU availability test failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get available TornadoVM devices.
     */
    public static java.util.List<TornadoDevice> getAvailableDevices() {
        java.util.List<TornadoDevice> devices = new java.util.ArrayList<>();
        
        try {
            TornadoRuntime runtime = TornadoRuntimeProvider.getTornadoRuntime();
            int numBackends = runtime.getNumBackends();
            
            for (int backendIdx = 0; backendIdx < numBackends; backendIdx++) {
                TornadoBackend backend = runtime.getBackend(backendIdx);
                int numDevices = backend.getNumDevices();
                
                for (int deviceIdx = 0; deviceIdx < numDevices; deviceIdx++) {
                    TornadoDevice device = backend.getDevice(deviceIdx);
                    devices.add(device);
                }
            }
        } catch (Throwable e) {
            // Catch all errors including ExceptionInInitializerError and NoClassDefFoundError
            logger.warn("TornadoVM not available: {}", e.getMessage());
        }
        
        return devices;
    }
}

