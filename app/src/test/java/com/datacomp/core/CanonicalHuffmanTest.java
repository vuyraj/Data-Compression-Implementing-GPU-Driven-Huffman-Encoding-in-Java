package com.datacomp.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Canonical Huffman coding.
 */
class CanonicalHuffmanTest {
    
    @Test
    void testBuildCodesFromUniformDistribution() {
        long[] frequencies = new long[256];
        for (int i = 0; i < 256; i++) {
            frequencies[i] = 100;
        }
        
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        
        assertNotNull(codes);
        
        // All symbols should have codes
        for (int i = 0; i < 256; i++) {
            assertNotNull(codes[i], "Code for symbol " + i + " should not be null");
            assertTrue(codes[i].getCodeLength() > 0);
        }
    }
    
    @Test
    void testBuildCodesFromSkewedDistribution() {
        long[] frequencies = new long[256];
        frequencies[0] = 1000;  // Very common
        frequencies[1] = 500;
        frequencies[2] = 250;
        for (int i = 3; i < 256; i++) {
            frequencies[i] = 1;  // Rare
        }
        
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        
        assertNotNull(codes);
        
        // More common symbols should have shorter codes
        assertTrue(codes[0].getCodeLength() <= codes[255].getCodeLength());
    }
    
    @Test
    void testSingleSymbol() {
        long[] frequencies = new long[256];
        frequencies[42] = 1000;
        
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        
        assertNotNull(codes);
        assertNotNull(codes[42]);
        assertEquals(1, codes[42].getCodeLength());
    }
    
    @Test
    void testEmptyFrequencies() {
        long[] frequencies = new long[256];
        
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        
        assertNotNull(codes);
    }
    
    @Test
    void testCanonicalProperty() {
        long[] frequencies = new long[256];
        for (int i = 0; i < 256; i++) {
            frequencies[i] = i + 1;
        }
        
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        
        // Verify canonical property: codes of same length are consecutive
        for (int len = 1; len <= 16; len++) {
            int prevCode = -1;
            
            for (int symbol = 0; symbol < 256; symbol++) {
                if (codes[symbol] != null && codes[symbol].getCodeLength() == len) {
                    int currentCode = codes[symbol].getCodeword();
                    
                    if (prevCode != -1) {
                        assertEquals(prevCode + 1, currentCode,
                            "Codes of same length should be consecutive");
                    }
                    
                    prevCode = currentCode;
                }
            }
        }
    }
    
    @Test
    void testDecoder() {
        long[] frequencies = new long[256];
        frequencies['A'] = 100;
        frequencies['B'] = 50;
        frequencies['C'] = 25;
        
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        CanonicalHuffman.HuffmanDecoder decoder = CanonicalHuffman.buildDecoder(codes);
        
        assertNotNull(decoder);
        assertTrue(decoder.getMaxCodeLength() > 0);
    }
}

