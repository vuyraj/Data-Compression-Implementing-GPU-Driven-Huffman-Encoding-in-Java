package com.datacomp.core;

/**
 * Fast table-based Huffman decoder using lookup tables instead of tree traversal.
 * This is 2-3× faster than bit-by-bit tree walking and GPU-friendly.
 * 
 * Algorithm:
 * 1. Build a lookup table for all possible N-bit prefixes
 * 2. Read N bits from stream
 * 3. Lookup table returns: [symbol, codeLength]
 * 4. Advance by codeLength bits
 * 5. Repeat
 */
public class TableBasedHuffmanDecoder {
    
    private static final int TABLE_BITS = 10; // 1024-entry lookup table
    private static final int TABLE_SIZE = 1 << TABLE_BITS;
    
    private final LookupEntry[] lookupTable;
    private final CanonicalHuffman.HuffmanDecoder fallbackDecoder; // For codes > TABLE_BITS
    private final int maxCodeLength;
    
    /**
     * Lookup table entry: pre-decoded symbol and its code length.
     */
    public static class LookupEntry {
        public final int symbol;      // Decoded symbol (0-255), or -1 for "use fallback"
        public final int codeLength;  // How many bits this symbol uses
        
        public LookupEntry(int symbol, int codeLength) {
            this.symbol = symbol;
            this.codeLength = codeLength;
        }
    }
    
    public TableBasedHuffmanDecoder(HuffmanCode[] codes) {
        this.lookupTable = new LookupEntry[TABLE_SIZE];
        this.fallbackDecoder = new CanonicalHuffman.HuffmanDecoder(codes);
        
        // Find max code length
        int maxLen = 0;
        for (HuffmanCode code : codes) {
            if (code != null && code.getCodeLength() > maxLen) {
                maxLen = code.getCodeLength();
            }
        }
        this.maxCodeLength = maxLen;
        
        // Build lookup table
        buildLookupTable(codes);
    }
    
    /**
     * Build the lookup table for fast decoding.
     * 
     * Strategy: For each possible TABLE_BITS pattern, pre-compute the decoded symbol.
     * 
     * Example: If symbol 'A' has code "101" (3 bits), then all 10-bit patterns
     * starting with "101" decode to 'A':
     *   1010000000 → A (use 3 bits)
     *   1010000001 → A (use 3 bits)
     *   1011111111 → A (use 3 bits)
     */
    private void buildLookupTable(HuffmanCode[] codes) {
        // Initialize all entries to "use fallback"
        for (int i = 0; i < TABLE_SIZE; i++) {
            lookupTable[i] = new LookupEntry(-1, 0);
        }
        
        // For each symbol with its Huffman code
        for (int symbol = 0; symbol < 256; symbol++) {
            HuffmanCode code = codes[symbol];
            if (code == null) continue;
            
            int codeLength = code.getCodeLength();
            int codeValue = code.getCodeword();
            
            if (codeLength <= TABLE_BITS) {
                // Short code: populate all matching table entries
                // Example: code "101" (3 bits) matches patterns "101xxxxxxx" (7 suffix bits)
                
                int numSuffixes = 1 << (TABLE_BITS - codeLength);
                int baseIndex = codeValue << (TABLE_BITS - codeLength);
                
                for (int suffix = 0; suffix < numSuffixes; suffix++) {
                    int tableIndex = baseIndex | suffix;
                    lookupTable[tableIndex] = new LookupEntry(symbol, codeLength);
                }
            } else {
                // Long code (rare): mark prefix for fallback decoding
                int prefix = codeValue >>> (codeLength - TABLE_BITS);
                if (lookupTable[prefix].symbol == -1) {
                    lookupTable[prefix] = new LookupEntry(-1, TABLE_BITS);
                }
            }
        }
    }
    
    /**
     * Decode all symbols from compressed data.
     * Returns the decompressed byte array.
     */
    public byte[] decode(byte[] compressedData, int outputSize) {
        byte[] output = new byte[outputSize];
        FastBitReader reader = new FastBitReader(compressedData);
        
        for (int i = 0; i < outputSize; i++) {
            int symbol = decodeNextSymbol(reader);
            if (symbol == -1) {
                throw new RuntimeException("Huffman decode error at position " + i);
            }
            output[i] = (byte) symbol;
        }
        
        return output;
    }
    
    /**
     * Decode a single symbol using table lookup.
     */
    private int decodeNextSymbol(FastBitReader reader) {
        // Peek at next TABLE_BITS without consuming
        int lookupBits = reader.peek(TABLE_BITS);
        LookupEntry entry = lookupTable[lookupBits];
        
        if (entry.symbol != -1) {
            // Fast path: symbol found in table (99% of cases)
            reader.advance(entry.codeLength);
            return entry.symbol;
        } else {
            // Slow path: code longer than TABLE_BITS (rare)
            return decodeWithFallback(reader);
        }
    }
    
    /**
     * Fallback decoder for codes longer than TABLE_BITS.
     * Uses the canonical Huffman decoder.
     */
    private int decodeWithFallback(FastBitReader reader) {
        int code = 0;
        for (int len = 1; len <= maxCodeLength; len++) {
            int bit = reader.read(1);
            code = (code << 1) | bit;
            
            int symbol = fallbackDecoder.decodeSymbol(code, len);
            if (symbol != -1) {
                return symbol;
            }
        }
        return -1; // Decode error
    }
    
    /**
     * Get the lookup table (for GPU kernel use).
     */
    public LookupEntry[] getLookupTable() {
        return lookupTable;
    }
    
    /**
     * Fast bit reader with peek() support for table-based decoding.
     * Reads bits MSB-first (most significant bit first) to match encoding.
     */
    public static class FastBitReader {
        private final byte[] data;
        private int bytePos;
        private int bitPos; // 0-7, current bit within current byte
        
        public FastBitReader(byte[] data) {
            this.data = data;
            this.bytePos = 0;
            this.bitPos = 0;
        }
        
        /**
         * Peek at next n bits without consuming them.
         * Returns bits MSB-first (same order as encoding).
         */
        public int peek(int n) {
            if (n > 24) {
                throw new IllegalArgumentException("Can't peek more than 24 bits");
            }
            
            int result = 0;
            int bitsRead = 0;
            int tempBytePos = bytePos;
            int tempBitPos = bitPos;
            
            // Read bits MSB-first from each byte
            while (bitsRead < n && tempBytePos < data.length) {
                // Read one bit from position (7 - tempBitPos) in current byte
                int bit = (data[tempBytePos] >> (7 - tempBitPos)) & 1;
                result = (result << 1) | bit;
                bitsRead++;
                tempBitPos++;
                
                if (tempBitPos >= 8) {
                    tempBitPos = 0;
                    tempBytePos++;
                }
            }
            
            // Pad with zeros if we ran out of data
            while (bitsRead < n) {
                result = (result << 1);
                bitsRead++;
            }
            
            return result;
        }
        
        /**
         * Read n bits and advance position.
         */
        public int read(int n) {
            int result = peek(n);
            advance(n);
            return result;
        }
        
        /**
         * Advance position by n bits.
         */
        public void advance(int n) {
            bitPos += n;
            while (bitPos >= 8 && bytePos < data.length) {
                bitPos -= 8;
                bytePos++;
            }
        }
    }
}
