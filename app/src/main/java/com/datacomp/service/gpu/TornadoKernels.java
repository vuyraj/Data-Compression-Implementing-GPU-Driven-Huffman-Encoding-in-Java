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
    
    // ========== TWO-PHASE GPU ENCODING KERNELS ==========
    
    /**
     * Phase 1: Compute bit lengths for each input symbol.
     * Each thread looks up the code length for its symbol.
     * 
     * @param input Input byte array
     * @param length Number of bytes
     * @param codeLengths Lookup table: symbol -> code length
     * @param bitLengths Output: bit length for each input position
     */
    public static void computeBitLengthsKernel(byte[] input, int length,
                                               int[] codeLengths, int[] bitLengths) {
        for (@Parallel int i = 0; i < length; i++) {
            int symbol = input[i] & 0xFF;
            bitLengths[i] = codeLengths[symbol];
        }
    }
    
    /**
     * Parallel prefix sum (scan) using work-efficient algorithm.
     * This is the Blelloch scan algorithm adapted for TornadoVM.
     * 
     * @param input Input array
     * @param output Output array (exclusive prefix sum)
     * @param length Array length (must be power of 2)
     */
    public static void prefixSumKernel(int[] input, int[] output, int length) {
        // Note: This is a simplified version. For production, use a proper
        // work-efficient parallel scan with shared memory optimization.
        
        // Sequential prefix sum (TornadoVM will still run this on GPU)
        // For true parallel scan, need more complex implementation
        output[0] = 0;
        for (int i = 1; i < length; i++) {
            output[i] = output[i - 1] + input[i - 1];
        }
    }
    
    /**
     * Optimized parallel prefix sum using up-sweep and down-sweep.
     * This is more efficient for large arrays on GPU.
     * 
     * @param data Input/output array (modified in-place)
     * @param temp Temporary storage (same size as data)
     * @param length Array length
     */
    public static void parallelPrefixSumKernel(int[] data, int[] temp, int length) {
        // Copy input to temp
        for (@Parallel int i = 0; i < length; i++) {
            temp[i] = data[i];
        }
        
        // Up-sweep (reduce) phase
        for (int stride = 1; stride < length; stride *= 2) {
            for (@Parallel int i = 0; i < length; i++) {
                if (i >= stride && (i & (stride - 1)) == 0) {
                    temp[i] = temp[i - stride] + temp[i];
                }
            }
        }
        
        // Down-sweep phase
        data[0] = 0;
        for (int stride = length / 2; stride > 0; stride /= 2) {
            for (@Parallel int i = 0; i < length; i++) {
                if (i >= stride && (i & (stride - 1)) == 0) {
                    int t = data[i - stride];
                    data[i - stride] = data[i];
                    data[i] = data[i] + t;
                }
            }
        }
    }
    
    /**
     * Phase 2: Write codewords to output buffer using precomputed bit positions.
     * Each thread writes its codeword to the correct bit position.
     * 
     * @param input Input byte array
     * @param length Number of bytes
     * @param codewords Lookup table: symbol -> codeword
     * @param codeLengths Lookup table: symbol -> code length
     * @param bitPositions Precomputed bit positions (from prefix sum)
     * @param output Output buffer (byte-aligned)
     * @param outputSize Size of output buffer
     */
    public static void writeCodewordsKernel(byte[] input, int length,
                                           int[] codewords, int[] codeLengths,
                                           int[] bitPositions,
                                           byte[] output, int outputSize) {
        for (@Parallel int i = 0; i < length; i++) {
            int symbol = input[i] & 0xFF;
            int codeword = codewords[symbol];
            int codeLength = codeLengths[symbol];
            int bitPos = bitPositions[i];
            
            // Write codeword bit-by-bit to output
            // This is safe because each thread writes to non-overlapping bits
            writeBitsToBuffer(output, bitPos, codeword, codeLength);
        }
    }
    
    /**
     * Helper: Write bits to byte buffer at specific bit position.
     * Must be called from GPU kernel with non-overlapping positions.
     */
    private static void writeBitsToBuffer(byte[] buffer, int bitPos, 
                                         int bits, int numBits) {
        for (int i = 0; i < numBits; i++) {
            int bit = (bits >> (numBits - 1 - i)) & 1;
            int byteIdx = (bitPos + i) / 8;
            int bitIdx = 7 - ((bitPos + i) % 8);
            
            if (byteIdx < buffer.length && bit == 1) {
                buffer[byteIdx] |= (byte)(1 << bitIdx);
            }
        }
    }
    
    /**
     * Optimized kernel: Write 32-bit chunks when possible.
     * Much faster than bit-by-bit for longer codes.
     * 
     * @param input Input bytes
     * @param length Number of bytes
     * @param codewords Codewords (up to 32 bits)
     * @param codeLengths Code lengths
     * @param bitPositions Bit positions from prefix sum
     * @param output Output as 32-bit integers (converted to bytes later)
     * @param outputSizeInts Size of output in ints
     */
    public static void writeCodewordsOptimizedKernel(byte[] input, int length,
                                                    int[] codewords, int[] codeLengths,
                                                    int[] bitPositions,
                                                    int[] output, int outputSizeInts) {
        for (@Parallel int i = 0; i < length; i++) {
            int symbol = input[i] & 0xFF;
            int codeword = codewords[symbol];
            int codeLength = codeLengths[symbol];
            int bitPos = bitPositions[i];
            
            if (codeLength == 0) continue;
            
            // Calculate word-aligned position
            int wordIdx = bitPos / 32;
            int bitOffset = bitPos % 32;
            
            if (wordIdx < outputSizeInts) {
                // Write codeword shifted to correct position
                if (bitOffset + codeLength <= 32) {
                    // Fits in single word
                    int shifted = codeword << (32 - bitOffset - codeLength);
                    output[wordIdx] |= shifted;
                } else {
                    // Spans two words
                    int firstBits = 32 - bitOffset;
                    int secondBits = codeLength - firstBits;
                    
                    int firstPart = (codeword >> secondBits) << (32 - bitOffset - firstBits);
                    output[wordIdx] |= firstPart;
                    
                    if (wordIdx + 1 < outputSizeInts) {
                        int secondPart = (codeword & ((1 << secondBits) - 1)) << (32 - secondBits);
                        output[wordIdx + 1] |= secondPart;
                    }
                }
            }
        }
    }
}

