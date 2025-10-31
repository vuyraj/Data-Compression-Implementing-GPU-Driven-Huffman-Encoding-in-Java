package com.datacomp.core;

/**
 * Represents a Huffman code for a symbol.
 */
public class HuffmanCode {
    private final int symbol;
    private final int codeLength;
    private final int codeword;
    
    public HuffmanCode(int symbol, int codeLength, int codeword) {
        this.symbol = symbol;
        this.codeLength = codeLength;
        this.codeword = codeword;
    }
    
    public int getSymbol() {
        return symbol;
    }
    
    public int getCodeLength() {
        return codeLength;
    }
    
    public int getCodeword() {
        return codeword;
    }
    
    @Override
    public String toString() {
        if (codeLength == 0) return "[]";
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = codeLength - 1; i >= 0; i--) {
            sb.append((codeword >> i) & 1);
        }
        return sb.toString();
    }
}

