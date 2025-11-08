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
            // Try to find a CUDA device first (more stable than OpenCL for NVIDIA GPUs)
            TornadoDevice cudaDevice = findCudaDevice();
            if (cudaDevice != null) {
                logger.info("Selected GPU device: {} (CUDA backend)", cudaDevice.getDescription());
                return cudaDevice;
            }
            
            // Fall back to default device
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
    
    /**
     * Find a CUDA device (PTX backend) if available.
     */
    private TornadoDevice findCudaDevice() {
        try {
            var runtime = TornadoRuntimeProvider.getTornadoRuntime();
            int numBackends = runtime.getNumBackends();
            
            logger.info("Searching for CUDA device among {} TornadoVM backends", numBackends);
            
            for (int backendIdx = 0; backendIdx < numBackends; backendIdx++) {
                var backend = runtime.getBackend(backendIdx);
                String backendName = backend.getName();
                logger.info("Backend {}: {} ({} devices)", backendIdx, backendName, backend.getNumDevices());
                
                // Look for PTX backend (CUDA)
                if (backendName.toLowerCase().contains("ptx") || 
                    backendName.toLowerCase().contains("cuda")) {
                    int numDevices = backend.getNumDevices();
                    if (numDevices > 0) {
                        TornadoDevice device = backend.getDevice(0);
                        logger.info("✅ Found CUDA/PTX device: {}", device.getDescription());
                        return device;
                    }
                } else {
                    logger.debug("Skipping non-CUDA backend: {}", backendName);
                }
            }
            logger.warn("⚠️  No CUDA/PTX backend found. Available backends don't include CUDA support.");
            logger.warn("⚠️  TornadoVM may not have been compiled with PTX backend.");
        } catch (Exception e) {
            logger.debug("Could not find CUDA device: {}", e.getMessage());
        }
        return null;
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
            
            // Create task graph and explicitly set it to use our selected device
            TaskGraph taskGraph = new TaskGraph("histogram")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, input)
                .task("compute", TornadoKernels::histogramKernel, input, length, histogram)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, histogram);
            
            ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
            
            try (TornadoExecutionPlan executor = new TornadoExecutionPlan(immutableTaskGraph)) {
                // Explicitly set the device to use (CUDA if available, otherwise default)
                if (device != null) {
                    executor.withDevice(device);
                }
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
    
    /**
     * Get the TornadoDevice being used.
     */
    public TornadoDevice getDevice() {
        return device;
    }
    
    /**
     * Get available GPU memory in bytes.
     * Returns conservative estimate based on device detection.
     */
    public long getAvailableMemoryBytes() {
        if (device == null) return 0;
        
        try {
            // Parse device description for memory hints
            String desc = device.getDescription().toLowerCase();
            
            // Common VRAM sizes and conservative usable amounts
            // We detect GPU model and estimate safely usable memory
            if (desc.contains("4090") || desc.contains("4080")) {
                return 16L * 1024 * 1024 * 1024; // 16GB for high-end GPUs (assume 24GB VRAM)
            } else if (desc.contains("4070") || desc.contains("4060") || desc.contains("3090") || desc.contains("3080")) {
                return 8L * 1024 * 1024 * 1024; // 8GB for mid-high GPUs (assume 12GB VRAM)
            } else if (desc.contains("3070") || desc.contains("3060") || desc.contains("2080") || desc.contains("2070")) {
                return 4L * 1024 * 1024 * 1024; // 4GB for mid GPUs (assume 8GB VRAM)
            } else if (desc.contains("3050") || desc.contains("2060") || desc.contains("1660") || 
                       desc.contains("1650") || desc.contains("mx550") || desc.contains("mx450")) {
                return 2L * 1024 * 1024 * 1024; // 2GB for entry GPUs (assume 4GB VRAM)
            } else if (desc.contains("mx350") || desc.contains("mx330") || desc.contains("mx250") || 
                       desc.contains("mx150") || desc.contains("gt 1030")) {
                return 800L * 1024 * 1024; // 800MB for low-end GPUs (assume 2GB VRAM)
            }
            
        } catch (Exception e) {
            logger.debug("Could not estimate GPU memory: {}", e.getMessage());
        }
        
        // Ultra-conservative fallback for unknown GPUs
        logger.warn("⚠️  Could not detect GPU memory size, using conservative default (512MB)");
        return 512L * 1024 * 1024; // 512MB safe default
    }
    
    @Override
    public String getServiceName() {
        if (device != null) {
            String desc = device.getDescription();
            // Add backend info to help identify CUDA vs OpenCL
            String backendInfo = getBackendName();
            
            // Remove the backend suffix from description if it's already there
            // e.g. "NVIDIA GeForce MX330 opencl-0-0" -> "NVIDIA GeForce MX330"
            if (desc.contains("opencl-") || desc.contains("ptx-") || desc.contains("cuda-")) {
                int lastSpace = desc.lastIndexOf(' ');
                if (lastSpace > 0) {
                    desc = desc.substring(0, lastSpace);
                }
            }
            
            return "GPU (" + desc + " [" + backendInfo + "])";
        }
        return "GPU (unavailable)";
    }
    
    /**
     * Get the backend name (CUDA/PTX or OpenCL) for the selected device.
     */
    private String getBackendName() {
        try {
            // Try to get backend name from the device's platform
            if (device != null) {
                String deviceDesc = device.getDescription().toLowerCase();
                // Check if description contains backend info
                if (deviceDesc.contains("ptx-") || deviceDesc.contains("cuda-")) {
                    return "PTX/CUDA";
                }
                if (deviceDesc.contains("opencl-")) {
                    return "OPENCL";
                }
            }
            
            // Fallback: search through all backends
            var runtime = TornadoRuntimeProvider.getTornadoRuntime();
            int numBackends = runtime.getNumBackends();
            
            for (int backendIdx = 0; backendIdx < numBackends; backendIdx++) {
                var backend = runtime.getBackend(backendIdx);
                int numDevices = backend.getNumDevices();
                
                for (int deviceIdx = 0; deviceIdx < numDevices; deviceIdx++) {
                    TornadoDevice dev = backend.getDevice(deviceIdx);
                    if (dev == device || dev.getDescription().equals(device.getDescription())) {
                        String name = backend.getName();
                        return name.toUpperCase();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not determine backend name: {}", e.getMessage());
        }
        return "UNKNOWN";
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
                // Explicitly set the device to use
                if (device != null) {
                    executor.withDevice(device);
                }
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

