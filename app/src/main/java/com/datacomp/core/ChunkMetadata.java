package com.datacomp.core;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Metadata for a compressed chunk.
 */
public class ChunkMetadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final int chunkIndex;
    private final long originalOffset;
    private final int originalSize;
    private final long compressedOffset;
    private final int compressedSize;
    private final byte[] sha256Checksum;
    private final int[] codeLengths; // Code lengths for canonical Huffman
    
    public ChunkMetadata(int chunkIndex, long originalOffset, int originalSize,
                        long compressedOffset, int compressedSize,
                        byte[] sha256Checksum, int[] codeLengths) {
        this.chunkIndex = chunkIndex;
        this.originalOffset = originalOffset;
        this.originalSize = originalSize;
        this.compressedOffset = compressedOffset;
        this.compressedSize = compressedSize;
        this.sha256Checksum = sha256Checksum;
        this.codeLengths = codeLengths;
    }
    
    public int getChunkIndex() { return chunkIndex; }
    public long getOriginalOffset() { return originalOffset; }
    public int getOriginalSize() { return originalSize; }
    public long getCompressedOffset() { return compressedOffset; }
    public int getCompressedSize() { return compressedSize; }
    public byte[] getSha256Checksum() { return sha256Checksum; }
    public int[] getCodeLengths() { return codeLengths; }
    
    public double getCompressionRatio() {
        if (originalSize == 0) return 1.0;
        return (double) compressedSize / originalSize;
    }
    
    @Override
    public String toString() {
        return String.format("Chunk[%d] offset=%d size=%d->%d ratio=%.2f%%",
            chunkIndex, originalOffset, originalSize, compressedSize,
            getCompressionRatio() * 100);
    }
}

