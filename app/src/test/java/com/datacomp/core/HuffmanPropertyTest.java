package com.datacomp.core;

import net.jqwik.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Huffman coding using jqwik.
 */
class HuffmanPropertyTest {
    
    @Property
    void huffmanCodesShouldBeUnique(@ForAll("frequencies") long[] frequencies) {
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        
        // Count non-null codes
        int numCodes = 0;
        for (HuffmanCode code : codes) {
            if (code != null) numCodes++;
        }
        
        if (numCodes <= 1) {
            return; // Edge case
        }
        
        // Verify uniqueness: no two codes with same length should have same codeword
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == null) continue;
            
            for (int j = i + 1; j < codes.length; j++) {
                if (codes[j] == null) continue;
                
                if (codes[i].getCodeLength() == codes[j].getCodeLength()) {
                    assertNotEquals(codes[i].getCodeword(), codes[j].getCodeword(),
                        "Codes of same length must have different codewords");
                }
            }
        }
    }
    
    @Property
    void frequentSymbolsShouldHaveShorterCodes(@ForAll("frequencies") long[] frequencies) {
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        
        // Find most and least frequent symbols
        int maxFreqSymbol = -1;
        int minFreqSymbol = -1;
        long maxFreq = 0;
        long minFreq = Long.MAX_VALUE;
        
        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] > maxFreq) {
                maxFreq = frequencies[i];
                maxFreqSymbol = i;
            }
            if (frequencies[i] > 0 && frequencies[i] < minFreq) {
                minFreq = frequencies[i];
                minFreqSymbol = i;
            }
        }
        
        if (maxFreqSymbol != -1 && minFreqSymbol != -1 && maxFreqSymbol != minFreqSymbol) {
            // Most frequent should have code length <= least frequent
            assertTrue(codes[maxFreqSymbol].getCodeLength() <= codes[minFreqSymbol].getCodeLength(),
                "More frequent symbols should have shorter or equal length codes");
        }
    }
    
    @Property
    void allSymbolsWithNonZeroFrequencyShouldHaveCode(@ForAll("frequencies") long[] frequencies) {
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        
        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] > 0) {
                assertNotNull(codes[i], "Symbol " + i + " with non-zero frequency should have a code");
                assertTrue(codes[i].getCodeLength() > 0);
            }
        }
    }
    
    @Provide
    Arbitrary<long[]> frequencies() {
        return Arbitraries.integers().between(0, 1000)
            .list().ofSize(256)
            .map(list -> list.stream().mapToLong(Integer::longValue).toArray())
            .filter(arr -> {
                // Ensure at least one non-zero frequency
                for (long freq : arr) {
                    if (freq > 0) return true;
                }
                return false;
            });
    }
}

