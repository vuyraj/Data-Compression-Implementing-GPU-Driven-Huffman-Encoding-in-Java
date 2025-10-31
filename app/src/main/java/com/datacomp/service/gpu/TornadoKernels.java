package com.datacomp.service.gpu;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.annotations.Reduce;

/**
 * TornadoVM GPU kernels for compression operations.
 * All methods must be static and use primitive arrays (TornadoVM constraints).
 */
public class TornadoKernels {
    
    /**
     * Parallel histogram kernel - compute byte frequencies.
     * Each thread processes a portion of input and updates local histogram.
     * 
     * @param input Input byte array
     * @param length Number of bytes to process
     * @param histogram Output histogram (256 bins)
     */
    public static void histogramKernel(byte[] input, int length, int[] histogram) {
        for (@Parallel int i = 0; i < length; i++) {
            int symbol = input[i] & 0xFF;
            histogram[symbol]++;
        }
    }
    
    /**
     * Parallel histogram with reduction (optimized version).
     * Uses local histograms per work group and reduces at the end.
     * 
     * @param input Input byte array
     * @param length Number of bytes to process
     * @param localHistograms Local histograms (workGroupSize * 256)
     * @param histogram Final histogram output
     */
    public static void histogramReductionKernel(byte[] input, int length,
                                                int[] localHistograms, int[] histogram) {
        // Phase 1: Each thread builds local histogram
        for (@Parallel int tid = 0; tid < length; tid++) {
            int symbol = input[tid] & 0xFF;
            int localIdx = (tid % 256) * 256 + symbol;
            localHistograms[localIdx]++;
        }
        
        // Phase 2: Reduce local histograms to final histogram
        for (@Parallel int symbol = 0; symbol < 256; symbol++) {
            int sum = 0;
            for (int worker = 0; worker < 256; worker++) {
                sum += localHistograms[worker * 256 + symbol];
            }
            histogram[symbol] = sum;
        }
    }
    
    /**
     * Encode kernel - parallel bit packing.
     * Each thread encodes a block of input bytes.
     * 
     * @param input Input bytes
     * @param length Number of bytes
     * @param codeLengths Code lengths for each symbol
     * @param codewords Codewords for each symbol
     * @param output Output buffer for compressed bits
     * @param outputOffsets Starting bit offset for each thread
     */
    public static void encodeKernel(byte[] input, int length,
                                    int[] codeLengths, int[] codewords,
                                    int[] output, int[] outputOffsets) {
        for (@Parallel int i = 0; i < length; i++) {
            int symbol = input[i] & 0xFF;
            int codeLength = codeLengths[symbol];
            int codeword = codewords[symbol];
            
            // Write codeword to output (simplified)
            int offset = outputOffsets[i];
            output[offset / 32] |= (codeword << (offset % 32));
        }
    }
    
    /**
     * Memory bandwidth test kernel.
     * 
     * @param input Input array
     * @param output Output array
     * @param size Array size
     */
    public static void memoryBandwidthKernel(float[] input, float[] output, int size) {
        for (@Parallel int i = 0; i < size; i++) {
            output[i] = input[i] * 2.0f;
        }
    }
    
    /**
     * Reduction kernel for testing parallel reduction.
     * 
     * @param input Input array
     * @param result Output sum
     */
    public static void reductionKernel(int[] input, @Reduce int[] result) {
        result[0] = 0;
        for (@Parallel int i = 0; i < input.length; i++) {
            result[0] += input[i];
        }
    }
    
    /**
     * Vector add kernel for testing.
     */
    public static void vectorAddKernel(float[] a, float[] b, float[] c, int size) {
        for (@Parallel int i = 0; i < size; i++) {
            c[i] = a[i] + b[i];
        }
    }
}

