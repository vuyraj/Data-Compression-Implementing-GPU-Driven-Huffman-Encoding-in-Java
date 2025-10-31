package com.datacomp.core;

import java.util.*;

/**
 * Canonical Huffman coding implementation for efficient compression.
 * Uses canonical form for simpler serialization and faster decoding.
 */
public class CanonicalHuffman {
    
    private static final int ALPHABET_SIZE = 256;
    
    /**
     * Build canonical Huffman codes from frequency histogram.
     * 
     * @param frequencies Frequency count for each byte value (0-255)
     * @return Array of HuffmanCode objects indexed by symbol
     */
    public static HuffmanCode[] buildCanonicalCodes(long[] frequencies) {
        if (frequencies.length != ALPHABET_SIZE) {
            throw new IllegalArgumentException("Frequency array must have 256 elements");
        }
        
        // Count non-zero frequencies
        int numSymbols = 0;
        for (long freq : frequencies) {
            if (freq > 0) numSymbols++;
        }
        
        // Handle edge cases
        if (numSymbols == 0) {
            return new HuffmanCode[ALPHABET_SIZE];
        }
        
        if (numSymbols == 1) {
            // Single symbol: use 1-bit code
            HuffmanCode[] codes = new HuffmanCode[ALPHABET_SIZE];
            for (int i = 0; i < ALPHABET_SIZE; i++) {
                if (frequencies[i] > 0) {
                    codes[i] = new HuffmanCode(i, 1, 0);
                    break;
                }
            }
            return codes;
        }
        
        // Build Huffman tree
        int[] codeLengths = buildCodeLengths(frequencies);
        
        // Generate canonical codes from lengths
        return generateCanonicalCodes(codeLengths);
    }
    
    /**
     * Build code lengths from frequencies using standard Huffman algorithm.
     */
    private static int[] buildCodeLengths(long[] frequencies) {
        PriorityQueue<HuffmanNode> queue = new PriorityQueue<>();
        
        // Create leaf nodes
        for (int i = 0; i < ALPHABET_SIZE; i++) {
            if (frequencies[i] > 0) {
                queue.offer(new HuffmanNode(i, frequencies[i]));
            }
        }
        
        // Build tree
        while (queue.size() > 1) {
            HuffmanNode left = queue.poll();
            HuffmanNode right = queue.poll();
            queue.offer(new HuffmanNode(left, right));
        }
        
        // Extract code lengths
        int[] codeLengths = new int[ALPHABET_SIZE];
        if (!queue.isEmpty()) {
            HuffmanNode root = queue.poll();
            extractLengths(root, 0, codeLengths);
        }
        
        return codeLengths;
    }
    
    /**
     * Recursively extract code lengths from tree.
     */
    private static void extractLengths(HuffmanNode node, int depth, int[] lengths) {
        if (node.isLeaf()) {
            lengths[node.getSymbol()] = depth;
        } else {
            extractLengths(node.getLeft(), depth + 1, lengths);
            extractLengths(node.getRight(), depth + 1, lengths);
        }
    }
    
    /**
     * Generate canonical codes from code lengths.
     * Canonical property: codes of same length are consecutive integers,
     * shorter codes have smaller numerical values.
     */
    private static HuffmanCode[] generateCanonicalCodes(int[] codeLengths) {
        // Find max length and count symbols per length
        int maxLength = 0;
        int[] lengthCounts = new int[33]; // Max possible Huffman depth
        
        for (int len : codeLengths) {
            if (len > 0) {
                lengthCounts[len]++;
                maxLength = Math.max(maxLength, len);
            }
        }
        
        // Compute starting codeword for each length
        int[] firstCode = new int[maxLength + 1];
        int code = 0;
        for (int len = 1; len <= maxLength; len++) {
            code = (code + lengthCounts[len - 1]) << 1;
            firstCode[len] = code;
        }
        
        // Assign canonical codes
        HuffmanCode[] codes = new HuffmanCode[ALPHABET_SIZE];
        int[] nextCode = Arrays.copyOf(firstCode, firstCode.length);
        
        for (int symbol = 0; symbol < ALPHABET_SIZE; symbol++) {
            int len = codeLengths[symbol];
            if (len > 0) {
                codes[symbol] = new HuffmanCode(symbol, len, nextCode[len]);
                nextCode[len]++;
            }
        }
        
        return codes;
    }
    
    /**
     * Build decoder lookup table for fast decompression.
     * 
     * @param codes Huffman codes
     * @return Decoder that maps (codeLength, codeword) -> symbol
     */
    public static HuffmanDecoder buildDecoder(HuffmanCode[] codes) {
        return new HuffmanDecoder(codes);
    }
    
    /**
     * Decoder for canonical Huffman codes.
     */
    public static class HuffmanDecoder {
        private final Map<Integer, Map<Integer, Integer>> lookupTable;
        private final int maxCodeLength;
        
        HuffmanDecoder(HuffmanCode[] codes) {
            this.lookupTable = new HashMap<>();
            int maxLen = 0;
            
            for (HuffmanCode code : codes) {
                if (code != null) {
                    int len = code.getCodeLength();
                    int codeword = code.getCodeword();
                    int symbol = code.getSymbol();
                    
                    lookupTable.computeIfAbsent(len, k -> new HashMap<>())
                              .put(codeword, symbol);
                    maxLen = Math.max(maxLen, len);
                }
            }
            
            this.maxCodeLength = maxLen;
        }
        
        /**
         * Decode a symbol from the bit buffer.
         * 
         * @param bits Bit buffer
         * @param bitPos Current bit position
         * @return Decoded symbol, or -1 if invalid
         */
        public int decode(long bits, int bitPos) {
            int code = 0;
            for (int len = 1; len <= maxCodeLength; len++) {
                code = (code << 1) | ((int)(bits >> (63 - bitPos)) & 1);
                bitPos++;
                
                Map<Integer, Integer> symbols = lookupTable.get(len);
                if (symbols != null) {
                    Integer symbol = symbols.get(code);
                    if (symbol != null) {
                        return symbol;
                    }
                }
            }
            return -1; // Invalid code
        }
        
        public int getMaxCodeLength() {
            return maxCodeLength;
        }
    }
}

