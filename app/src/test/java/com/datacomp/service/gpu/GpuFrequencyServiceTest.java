package com.datacomp.service.gpu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * GPU tests - skip if GPU not available.
 */
@Tag("gpu")
class GpuFrequencyServiceTest {
    
    private GpuFrequencyService service;
    
    @BeforeEach
    void setUp() {
        try {
            service = new GpuFrequencyService();
        } catch (Exception e) {
            // GPU not available
        }
    }
    
    @Test
    void testGpuAvailability() {
        if (service == null || !service.isAvailable()) {
            System.out.println("GPU not available, skipping test");
            return;
        }
        
        assertTrue(service.isAvailable());
        assertNotNull(service.getServiceName());
    }
    
    @Test
    @EnabledIfSystemProperty(named = "tornado.unittests.device", matches = ".*:.*")
    void testGpuHistogram() {
        assumeTrue(service != null && service.isAvailable(), "GPU not available");
        
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 10);
        }
        
        long[] histogram = service.computeHistogram(data, 0, data.length);
        
        assertNotNull(histogram);
        assertEquals(256, histogram.length);
        
        // Verify frequencies
        for (int i = 0; i < 10; i++) {
            assertTrue(histogram[i] > 0);
        }
    }
    
    @Test
    @EnabledIfSystemProperty(named = "tornado.unittests.device", matches = ".*:.*")
    void testGpuHistogramLargeData() {
        assumeTrue(service != null && service.isAvailable(), "GPU not available");
        
        byte[] data = new byte[1024 * 1024]; // 1MB
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        
        long[] histogram = service.computeHistogram(data, 0, data.length);
        
        assertNotNull(histogram);
        
        // Each byte value should appear ~4096 times
        for (int i = 0; i < 256; i++) {
            assertTrue(histogram[i] > 0);
        }
    }
}

