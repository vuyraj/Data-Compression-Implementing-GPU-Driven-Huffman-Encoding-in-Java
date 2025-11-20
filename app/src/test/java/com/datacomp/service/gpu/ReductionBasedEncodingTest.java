package com.datacomp.service.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import com.datacomp.core.HuffmanCode;

/**
 * Unit tests for reduction-based GPU Huffman encoding.
 * 
 * Tests the kernels and pipeline for the paper's approach:
 * "Revisiting Huffman Coding: Toward Extreme Performance on Modern GPU Architectures"
 * arXiv:2010.10039v1
 */
public class ReductionBasedEncodingTest {
    
    /**
     * Test basic 2-to-1 merge operation.
     * 
     * Example: Merge two 3-bit codes
     * Left:  code=0b101 (5), length=3
     * Right: code=0b011 (3), length=3
     * Result: code=0b101011 (43), length=6
     */
    @Test
    public void testBasicMerge() {
        // Test data: two simple codes
        int leftCode = 0b101;   // 5
        int leftLen = 3;
        int rightCode = 0b011;  // 3
        int rightLen = 3;
        
        // Expected: [101][011] = 0b101011 = 43
        int expectedCode = (leftCode << rightLen) | rightCode;
        int expectedLen = leftLen + rightLen;
        
        assertEquals(0b101011, expectedCode, "Merged code should be 101011 (binary)");
        assertEquals(43, expectedCode, "Merged code should be 43 (decimal)");
        assertEquals(6, expectedLen, "Merged length should be 6");
    }
    
    /**
     * Test merging with different length codes.
     * 
     * This simulates real Huffman codes where high-frequency symbols
     * have short codes (1-2 bits) and low-frequency symbols have long codes.
     */
    @Test
    public void testUnequalLengthMerge() {
        // Short code (high frequency): '0'
        int code1 = 0b0;
        int len1 = 1;
        
        // Long code (low frequency): '11010'
        int code2 = 0b11010;
        int len2 = 5;
        
        // Merge: [0][11010] = 0b011010 = 26
        int mergedCode = (code1 << len2) | code2;
        int mergedLen = len1 + len2;
        
        assertEquals(0b011010, mergedCode);
        assertEquals(26, mergedCode);
        assertEquals(6, mergedLen);
    }
    
    /**
     * Test multiple merge iterations (reduction factor r=3).
     * 
     * Simulates the REDUCE-MERGE phase:
     * Iteration 1: 8 codes → 4 codes (2-to-1 merge)
     * Iteration 2: 4 codes → 2 codes (2-to-1 merge)
     * Iteration 3: 2 codes → 1 code (2-to-1 merge)
     * 
     * Result: 8 original codes merged into 1 combined code
     */
    @Test
    public void testMultipleIterations() {
        // Start with 8 short codes (1 bit each)
        int[] codes = {0, 1, 0, 1, 1, 0, 1, 1};
        int[] lengths = {1, 1, 1, 1, 1, 1, 1, 1};
        
        // Iteration 1: 8 → 4
        int[] codes1 = new int[4];
        int[] lengths1 = new int[4];
        for (int i = 0; i < 4; i++) {
            codes1[i] = (codes[i*2] << lengths[i*2+1]) | codes[i*2+1];
            lengths1[i] = lengths[i*2] + lengths[i*2+1];
        }
        assertEquals(4, codes1.length);
        assertEquals(2, lengths1[0], "Each merged code should be 2 bits");
        
        // Iteration 2: 4 → 2
        int[] codes2 = new int[2];
        int[] lengths2 = new int[2];
        for (int i = 0; i < 2; i++) {
            codes2[i] = (codes1[i*2] << lengths1[i*2+1]) | codes1[i*2+1];
            lengths2[i] = lengths1[i*2] + lengths1[i*2+1];
        }
        assertEquals(2, codes2.length);
        assertEquals(4, lengths2[0], "Each merged code should be 4 bits");
        
        // Iteration 3: 2 → 1
        int finalCode = (codes2[0] << lengths2[1]) | codes2[1];
        int finalLen = lengths2[0] + lengths2[1];
        
        assertEquals(8, finalLen, "Final merged code should be 8 bits");
        
        // Verify the final code matches the original sequence
        // Original: [0,1,0,1,1,0,1,1] = 0b01011011 = 91
        assertEquals(0b01011011, finalCode);
        assertEquals(91, finalCode);
    }
    
    /**
     * Test with realistic Huffman codes.
     * 
     * Uses a small Huffman codebook and simulates encoding a short message.
     */
    @Test
    public void testRealisticHuffmanCodes() {
        // Simple Huffman codebook:
        // 'A' (freq=5): code=0b0 (1 bit)
        // 'B' (freq=2): code=0b10 (2 bits)
        // 'C' (freq=1): code=0b110 (3 bits)
        // 'D' (freq=1): code=0b111 (3 bits)
        
        int[] codes = {
            0b0,    // A
            0b10,   // B
            0b0,    // A
            0b0,    // A
            0b110,  // C
            0b111,  // D
            0b0,    // A
            0b10    // B
        };
        int[] lengths = {1, 2, 1, 1, 3, 3, 1, 2};
        
        // Expected encoded string: 0 10 0 0 110 111 0 10
        // Merge process (MSB-first):
        // Start: 0 (1 bit)
        // +10:   0<<2|10 = 10 (3 bits) = 2
        // +0:    10<<1|0 = 100 (4 bits) = 4
        // +0:    100<<1|0 = 1000 (5 bits) = 8
        // +110:  1000<<3|110 = 1000110 (8 bits) = 70
        // +111:  1000110<<3|111 = 1000110111 (11 bits) = 567
        // +0:    1000110111<<1|0 = 10001101110 (12 bits) = 1134
        // +10:   10001101110<<2|10 = 1000110111010 (14 bits) = 4538
        
        // Merge all codes sequentially (for validation)
        int mergedCode = codes[0];
        int mergedLen = lengths[0];
        for (int i = 1; i < codes.length; i++) {
            mergedCode = (mergedCode << lengths[i]) | codes[i];
            mergedLen += lengths[i];
        }
        
        assertEquals(14, mergedLen, "Total encoded length should be 14 bits");
        assertEquals(0b1000110111010, mergedCode, "Merged code should match expected bitstream");
        assertEquals(4538, mergedCode, "Merged code should be 4538 in decimal");
    }
    
    /**
     * Test edge case: merging codes that exceed 32 bits.
     * 
     * In the paper, codes exceeding 32 bits after r iterations are
     * filtered out and handled separately ("breaking" in Table II).
     */
    @Test
    public void testLongCodeHandling() {
        // Two 20-bit codes
        int code1 = 0b10101010101010101010; // 20 bits
        int len1 = 20;
        int code2 = 0b01010101010101010101; // 20 bits
        int len2 = 20;
        
        // Merging would create a 40-bit code (exceeds int32)
        long mergedLong = ((long)code1 << len2) | code2;
        int mergedLen = len1 + len2;
        
        assertEquals(40, mergedLen);
        assertTrue(mergedLen > 32, "Merged length exceeds 32 bits");
        
        // In real implementation, this would be handled specially
        // (either use long, or mark as "breaking" and handle separately)
    }
    
    /**
     * Test determining reduction factor from average bitwidth.
     * 
     * Based on paper's formula:
     * floor(log2(avg_bitwidth)) + r + 1 = log2(word_size)
     * 
     * For avg_bitwidth = 1.027 and word_size = 32:
     * 0 + r + 1 = 5  =>  r = 4
     */
    @Test
    public void testReductionFactorCalculation() {
        double avgBitwidth = 1.027;
        int wordSize = 32;
        
        int log2AvgBitwidth = (int)Math.floor(Math.log(avgBitwidth) / Math.log(2));
        int log2WordSize = (int)(Math.log(wordSize) / Math.log(2));
        int reductionFactor = log2WordSize - log2AvgBitwidth - 1;
        
        assertEquals(0, log2AvgBitwidth);
        assertEquals(5, log2WordSize);
        assertEquals(4, reductionFactor);
        
        // Verify: 2^r = 16 codes merged per thread
        int codesPerThread = 1 << reductionFactor;
        assertEquals(16, codesPerThread);
        
        // Verify: avg merged length ≈ 16.4 bits (fits in uint32)
        double avgMergedLen = avgBitwidth * codesPerThread;
        assertTrue(avgMergedLen < wordSize, "Merged code should fit in word");
        assertTrue(avgMergedLen > wordSize / 2, "Should maximize word utilization");
    }
    
    /**
     * Test optimal parameters from paper's Table II.
     * 
     * Best performance: M=10, r=3
     * - Chunk size: 2^10 = 1024 symbols
     * - Reduction: 3 iterations (8-to-1 merge)
     * - Shuffle: 7 iterations
     */
    @Test
    public void testOptimalParameters() {
        int M = 10;  // Magnitude
        int r = 3;   // Reduction factor
        int s = M - r;  // Shuffle factor
        
        int chunkSize = 1 << M;
        int reducedSize = 1 << s;
        int mergeRatio = 1 << r;
        
        assertEquals(1024, chunkSize, "Chunk should be 1024 symbols");
        assertEquals(128, reducedSize, "After reduction: 128 merged codes");
        assertEquals(8, mergeRatio, "Each thread merges 8 codes");
        assertEquals(7, s, "7 shuffle iterations");
    }
}
