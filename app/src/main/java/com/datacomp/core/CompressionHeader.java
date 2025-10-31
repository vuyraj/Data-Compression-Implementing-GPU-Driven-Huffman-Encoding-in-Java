package com.datacomp.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Header for compressed file format.
 * Contains magic number, version, original file metadata, and chunk table.
 */
public class CompressionHeader implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public static final int MAGIC_NUMBER = 0x44435A46; // "DCZF" - DataComp Zipped File
    public static final int VERSION = 1;
    
    private final String originalFileName;
    private final long originalFileSize;
    private final long originalTimestamp;
    private final byte[] globalChecksum;
    private final List<ChunkMetadata> chunks;
    private final int chunkSizeBytes;
    
    public CompressionHeader(String originalFileName, long originalFileSize,
                           long originalTimestamp, byte[] globalChecksum,
                           int chunkSizeBytes) {
        this.originalFileName = originalFileName;
        this.originalFileSize = originalFileSize;
        this.originalTimestamp = originalTimestamp;
        this.globalChecksum = globalChecksum;
        this.chunks = new ArrayList<>();
        this.chunkSizeBytes = chunkSizeBytes;
    }
    
    public void addChunk(ChunkMetadata chunk) {
        chunks.add(chunk);
    }
    
    public String getOriginalFileName() { return originalFileName; }
    public long getOriginalFileSize() { return originalFileSize; }
    public long getOriginalTimestamp() { return originalTimestamp; }
    public byte[] getGlobalChecksum() { return globalChecksum; }
    public List<ChunkMetadata> getChunks() { return chunks; }
    public int getChunkSizeBytes() { return chunkSizeBytes; }
    public int getNumChunks() { return chunks.size(); }
    
    /**
     * Write header to output stream.
     */
    public void writeTo(DataOutputStream out) throws IOException {
        // Magic number and version
        out.writeInt(MAGIC_NUMBER);
        out.writeInt(VERSION);
        
        // Original file metadata
        byte[] fileNameBytes = originalFileName.getBytes(StandardCharsets.UTF_8);
        out.writeInt(fileNameBytes.length);
        out.write(fileNameBytes);
        out.writeLong(originalFileSize);
        out.writeLong(originalTimestamp);
        out.writeInt(chunkSizeBytes);
        
        // Global checksum
        out.write(globalChecksum);
        
        // Chunk count
        out.writeInt(chunks.size());
        
        // Write chunk metadata
        for (ChunkMetadata chunk : chunks) {
            out.writeInt(chunk.getChunkIndex());
            out.writeLong(chunk.getOriginalOffset());
            out.writeInt(chunk.getOriginalSize());
            out.writeLong(chunk.getCompressedOffset());
            out.writeInt(chunk.getCompressedSize());
            out.write(chunk.getSha256Checksum());
            
            // Write code lengths (256 integers)
            int[] codeLengths = chunk.getCodeLengths();
            for (int len : codeLengths) {
                out.writeShort(len);
            }
        }
    }
    
    /**
     * Read header from input stream.
     */
    public static CompressionHeader readFrom(DataInputStream in) throws IOException {
        // Verify magic number and version
        int magic = in.readInt();
        if (magic != MAGIC_NUMBER) {
            throw new IOException("Invalid file format: bad magic number");
        }
        
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported version: " + version);
        }
        
        // Read original file metadata
        int nameLen = in.readInt();
        byte[] nameBytes = new byte[nameLen];
        in.readFully(nameBytes);
        String fileName = new String(nameBytes, StandardCharsets.UTF_8);
        
        long fileSize = in.readLong();
        long timestamp = in.readLong();
        int chunkSize = in.readInt();
        
        // Read global checksum
        byte[] globalChecksum = new byte[32]; // SHA-256
        in.readFully(globalChecksum);
        
        CompressionHeader header = new CompressionHeader(
            fileName, fileSize, timestamp, globalChecksum, chunkSize);
        
        // Read chunk metadata
        int numChunks = in.readInt();
        for (int i = 0; i < numChunks; i++) {
            int chunkIndex = in.readInt();
            long originalOffset = in.readLong();
            int originalSize = in.readInt();
            long compressedOffset = in.readLong();
            int compressedSize = in.readInt();
            
            byte[] checksum = new byte[32];
            in.readFully(checksum);
            
            // Read code lengths
            int[] codeLengths = new int[256];
            for (int j = 0; j < 256; j++) {
                codeLengths[j] = in.readShort();
            }
            
            ChunkMetadata chunk = new ChunkMetadata(
                chunkIndex, originalOffset, originalSize,
                compressedOffset, compressedSize, checksum, codeLengths);
            header.addChunk(chunk);
        }
        
        return header;
    }
}

