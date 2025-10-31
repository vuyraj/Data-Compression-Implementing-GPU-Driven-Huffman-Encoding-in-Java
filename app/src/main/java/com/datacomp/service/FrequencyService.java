package com.datacomp.service;

/**
 * Service for computing byte frequency histograms.
 */
public interface FrequencyService {
    
    /**
     * Compute frequency histogram for a byte array.
     * 
     * @param data Input data
     * @param offset Starting offset
     * @param length Number of bytes to process
     * @return Frequency array (256 elements)
     */
    long[] computeHistogram(byte[] data, int offset, int length);
    
    /**
     * Get service name.
     */
    String getServiceName();
    
    /**
     * Check if service is available.
     */
    boolean isAvailable();
}

