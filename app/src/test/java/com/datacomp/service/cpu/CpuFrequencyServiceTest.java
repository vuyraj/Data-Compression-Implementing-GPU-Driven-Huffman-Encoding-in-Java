package com.datacomp.service.cpu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CPU frequency service.
 */
class CpuFrequencyServiceTest {
    
    private CpuFrequencyService service;
    
    @BeforeEach
    void setUp() {
        service = new CpuFrequencyService();
    }
    
    @Test
    void testServiceAvailable() {
        assertTrue(service.isAvailable());
    }
    
    @Test
    void testHistogramSmallData() {
        byte[] data = {0, 1, 2, 1, 0, 1};
        
        long[] histogram = service.computeHistogram(data, 0, data.length);
        
        assertNotNull(histogram);
        assertEquals(256, histogram.length);
        assertEquals(2, histogram[0]);
        assertEquals(3, histogram[1]);
        assertEquals(1, histogram[2]);
    }
    
    @Test
    void testHistogramAllBytes() {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) i;
        }
        
        long[] histogram = service.computeHistogram(data, 0, data.length);
        
        for (int i = 0; i < 256; i++) {
            assertEquals(1, histogram[i], "Frequency of byte " + i);
        }
    }
    
    @Test
    void testHistogramLargeData() {
        // Test with data larger than parallel threshold
        byte[] data = new byte[128 * 1024]; // 128KB
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 10);
        }
        
        long[] histogram = service.computeHistogram(data, 0, data.length);
        
        assertNotNull(histogram);
        
        // Verify counts
        for (int i = 0; i < 10; i++) {
            assertTrue(histogram[i] > 0);
        }
    }
    
    @Test
    void testHistogramWithOffset() {
        byte[] data = new byte[100];
        for (int i = 0; i < 100; i++) {
            data[i] = (byte) (i < 50 ? 5 : 10);
        }
        
        long[] histogram = service.computeHistogram(data, 25, 50);
        
        assertEquals(25, histogram[5]);
        assertEquals(25, histogram[10]);
    }
    
    @Test
    void testHistogramNegativeBytes() {
        byte[] data = {-1, -2, -3, -1};
        
        long[] histogram = service.computeHistogram(data, 0, data.length);
        
        assertEquals(2, histogram[255]); // -1 as unsigned
        assertEquals(1, histogram[254]); // -2 as unsigned
        assertEquals(1, histogram[253]); // -3 as unsigned
    }
}

