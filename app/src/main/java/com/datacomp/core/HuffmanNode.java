package com.datacomp.core;

/**
 * Node in the Huffman tree for canonical Huffman coding.
 */
public class HuffmanNode implements Comparable<HuffmanNode> {
    private final int symbol;
    private final long frequency;
    private final HuffmanNode left;
    private final HuffmanNode right;
    
    /**
     * Create a leaf node.
     */
    public HuffmanNode(int symbol, long frequency) {
        this.symbol = symbol;
        this.frequency = frequency;
        this.left = null;
        this.right = null;
    }
    
    /**
     * Create an internal node.
     */
    public HuffmanNode(HuffmanNode left, HuffmanNode right) {
        this.symbol = -1;
        this.frequency = left.frequency + right.frequency;
        this.left = left;
        this.right = right;
    }
    
    public boolean isLeaf() {
        return left == null && right == null;
    }
    
    public int getSymbol() {
        return symbol;
    }
    
    public long getFrequency() {
        return frequency;
    }
    
    public HuffmanNode getLeft() {
        return left;
    }
    
    public HuffmanNode getRight() {
        return right;
    }
    
    @Override
    public int compareTo(HuffmanNode other) {
        int cmp = Long.compare(this.frequency, other.frequency);
        if (cmp != 0) return cmp;
        // Tie-breaker for stable sorting
        return Integer.compare(this.symbol, other.symbol);
    }
}

