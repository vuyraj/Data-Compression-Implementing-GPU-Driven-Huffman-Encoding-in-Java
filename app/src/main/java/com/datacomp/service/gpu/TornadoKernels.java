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
     * Tiled histogram kernel - Race-free.
     * Each thread processes a distinct chunk of input and writes to its own histogram.
     * 
     * @param input Input byte array
     * @param length Total length of input
     * @param subHistograms Output: [numChunks * 256]
     * @param chunkSize Size of each chunk processed by a thread
     */
    public static void histogramTiledKernel(byte[] input, int length, int[] subHistograms, int chunkSize) {
        for (@Parallel int chunkId = 0; chunkId < (length + chunkSize - 1) / chunkSize; chunkId++) {
            int start = chunkId * chunkSize;
            int end = Math.min(start + chunkSize, length);
            int histOffset = chunkId * 256;
            
            for (int i = start; i < end; i++) {
                int symbol = input[i] & 0xFF;
                subHistograms[histOffset + symbol]++;
            }
        }
    }

    /**
     * Packet-based encoding kernel - Race-free.
     * Each thread generates exactly one 32-bit output word.
     * It finds the relevant input symbols using binary search on bitPositions.
     * 
     * @param input Input symbols
     * @param codewords Huffman codewords
     * @param codeLengths Huffman code lengths
     * @param bitPositions Prefix sum of bit positions
     * @param output Output array (ints)
     * @param numSymbols Number of input symbols
     * @param numOutputWords Number of output words to generate
     */
    public static void encodePacketKernel(byte[] input, int[] codewords, int[] codeLengths, 
                                         int[] bitPositions, int[] output, 
                                         int numSymbols, int numOutputWords) {
        for (@Parallel int wordIdx = 0; wordIdx < numOutputWords; wordIdx++) {
            int startBit = wordIdx * 32;
            int endBit = startBit + 32;
            
            // Binary search to find the first symbol that contributes to this word
            // We look for the first symbol where bitPositions[i] + codeLength > startBit
            // Or simply, the symbol covering startBit.
            // Since bitPositions[i] is the START bit of symbol i.
            // We want largest i such that bitPositions[i] <= startBit + 31 (roughly)
            // Actually, we want the first symbol that *ends* after startBit.
            // i.e. bitPositions[i] + codeLengths[i] > startBit.
            
            // Binary search for 'startBit' in bitPositions
            int low = 0;
            int high = numSymbols - 1;
            int startSymbolIdx = -1;
            
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (bitPositions[mid] <= startBit) {
                    startSymbolIdx = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            
            // startSymbolIdx is the last symbol that starts at or before startBit.
            // It might end before startBit (if there's a gap? No, packed).
            // So startSymbolIdx is the symbol covering startBit (or the one just before if it ended exactly at startBit).
            
            if (startSymbolIdx < 0) startSymbolIdx = 0;
            
            int currentWord = 0;
            
            // Iterate through symbols until we pass endBit
            for (int i = startSymbolIdx; i < numSymbols; i++) {
                int symStartBit = bitPositions[i];
                int symLen = codeLengths[input[i] & 0xFF];
                int symEndBit = symStartBit + symLen;
                
                if (symStartBit >= endBit) break; // Past this word
                
                // Calculate overlap with this word [startBit, endBit)
                // Intersection of [symStartBit, symEndBit) and [startBit, endBit)
                
                int overlapStart = Math.max(startBit, symStartBit);
                int overlapEnd = Math.min(endBit, symEndBit);
                
                if (overlapStart < overlapEnd) {
                    int overlapLen = overlapEnd - overlapStart;
                    
                    // Extract bits from codeword
                    // Codeword bits are MSB aligned in the integer? No, usually LSB or just value.
                    // Let's assume 'codeword' has value. 
                    // Bit 0 of codeword is LSB? Or MSB?
                    // Usually Huffman codes are written MSB first.
                    // Let's assume bit 0 is the MSB of the code (value >> (len-1)).
                    
                    // We need bits from (overlapStart - symStartBit) to (overlapEnd - symStartBit)
                    // relative to the symbol's start.
                    
                    int bitsToShift = symLen - (overlapEnd - symStartBit);
                    int extractedBits = (codewords[input[i] & 0xFF] >>> bitsToShift) & ((1 << overlapLen) - 1);
                    
                    // Place into currentWord
                    // Word is filled from MSB to LSB? Or LSB to MSB?
                    // Let's assume standard big-endian bit stream:
                    // Byte 0: bits 0-7. Word 0: bits 0-31.
                    // Bit 0 of Word 0 is MSB (1<<31) or LSB (1<<0)?
                    // If we write `int` to file, DataOutputStream writes Big Endian.
                    // So `int` 0x12345678 becomes bytes 12, 34, 56, 78.
                    // Bit 0 of the stream is bit 31 of the int (MSB).
                    
                    int shiftInWord = 32 - (overlapEnd - startBit); // Shift to position
                    // Wait, if we fill from MSB (bit 31) down to LSB (bit 0).
                    // overlapStart relative to startBit:
                    // offset = overlapStart - startBit (0 to 31)
                    // We want to place these bits at 31 - offset - overlapLen + 1?
                    // Let's simplify:
                    // We want to place bits at `32 - (overlapEnd - startBit)`.
                    
                    currentWord |= (extractedBits << shiftInWord);
                }
            }
            output[wordIdx] = currentWord;
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

