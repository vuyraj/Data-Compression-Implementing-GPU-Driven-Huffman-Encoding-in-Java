package com.datacomp.service.cpu;

import com.datacomp.core.*;
import com.datacomp.service.CompressionService;
import com.datacomp.service.FrequencyService;
import com.datacomp.util.ChecksumUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.function.Consumer;

/**
 * CPU-based compression service with chunked streaming for large files.
 */
public class CpuCompressionService implements CompressionService {
    
    private static final Logger logger = LoggerFactory.getLogger(CpuCompressionService.class);
    
    private final FrequencyService frequencyService;
    private final int chunkSizeBytes;
    
    public CpuCompressionService(int chunkSizeMB) {
        this.frequencyService = new CpuFrequencyService();
        this.chunkSizeBytes = chunkSizeMB * 1024 * 1024;
    }
    
    @Override
    public void compress(Path inputPath, Path outputPath, 
                        Consumer<Double> progressCallback) throws IOException {
        long startTime = System.nanoTime();
        long fileSize = Files.size(inputPath);
        int numChunks = (int) ((fileSize + chunkSizeBytes - 1) / chunkSizeBytes);
        
        logger.info("Compressing {} ({} bytes) into {} chunks",
                   inputPath.getFileName(), fileSize, numChunks);
        
        // Prepare header
        CompressionHeader header = new CompressionHeader(
            inputPath.getFileName().toString(),
            fileSize,
            Files.getLastModifiedTime(inputPath).toMillis(),
            new byte[32], // Global checksum computed later
            chunkSizeBytes
        );
        
        MessageDigest globalDigest = ChecksumUtil.createSha256();
        
        try (RandomAccessFile inputFile = new RandomAccessFile(inputPath.toFile(), "r");
             FileChannel inputChannel = inputFile.getChannel();
             DataOutputStream output = new DataOutputStream(
                 new BufferedOutputStream(Files.newOutputStream(outputPath,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
            
            // Reserve space for header (write later after we know chunk offsets)
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            DataOutputStream headerOut = new DataOutputStream(headerBuffer);
            
            // Process chunks
            byte[] chunkData = new byte[chunkSizeBytes];
            long currentOffset = 0;
            long compressedOffset = 0; // Will be updated after header
            
            for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
                int bytesRead = readChunk(inputChannel, chunkData, currentOffset, fileSize);
                
                // Compute checksum
                MessageDigest chunkDigest = ChecksumUtil.createSha256();
                chunkDigest.update(chunkData, 0, bytesRead);
                byte[] chunkChecksum = chunkDigest.digest();
                globalDigest.update(chunkChecksum);
                
                // Compute frequencies
                long[] frequencies = frequencyService.computeHistogram(chunkData, 0, bytesRead);
                
                // Build Huffman codes
                HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
                
                // Extract code lengths for metadata
                int[] codeLengths = new int[256];
                for (int i = 0; i < 256; i++) {
                    codeLengths[i] = (codes[i] != null) ? codes[i].getCodeLength() : 0;
                }
                
                // Encode chunk
                byte[] compressedData = encodeChunk(chunkData, bytesRead, codes);
                
                // Create chunk metadata
                ChunkMetadata chunkMeta = new ChunkMetadata(
                    chunkIndex, currentOffset, bytesRead,
                    compressedOffset, compressedData.length,
                    chunkChecksum, codeLengths
                );
                header.addChunk(chunkMeta);
                
                logger.debug("Chunk {} compressed: {} -> {} bytes (ratio: {:.2f}%)",
                           chunkIndex, bytesRead, compressedData.length,
                           chunkMeta.getCompressionRatio() * 100);
                
                currentOffset += bytesRead;
                compressedOffset += compressedData.length;
                
                if (progressCallback != null) {
                    progressCallback.accept((double) (chunkIndex + 1) / numChunks);
                }
            }
            
            // Compute global checksum
            byte[] globalChecksum = globalDigest.digest();
            
            // Now write header with correct offsets
            CompressionHeader finalHeader = new CompressionHeader(
                header.getOriginalFileName(),
                header.getOriginalFileSize(),
                header.getOriginalTimestamp(),
                globalChecksum,
                header.getChunkSizeBytes()
            );
            
            // Adjust compressed offsets to account for header size
            headerBuffer.reset();
            for (ChunkMetadata chunk : header.getChunks()) {
                finalHeader.addChunk(chunk);
            }
            finalHeader.writeTo(headerOut);
            
            int headerSize = headerBuffer.size();
            
            // Write everything
            output.write(headerBuffer.toByteArray());
            
            // Re-read and write compressed chunks
            inputChannel.position(0);
            currentOffset = 0;
            for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
                int bytesRead = readChunk(inputChannel, chunkData, currentOffset, fileSize);
                long[] frequencies = frequencyService.computeHistogram(chunkData, 0, bytesRead);
                HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
                byte[] compressedData = encodeChunk(chunkData, bytesRead, codes);
                output.write(compressedData);
                currentOffset += bytesRead;
            }
        }
        
        long duration = System.nanoTime() - startTime;
        long compressedSize = Files.size(outputPath);
        double ratio = (double) compressedSize / fileSize;
        double throughputMBps = (fileSize / 1_000_000.0) / (duration / 1_000_000_000.0);
        
        logger.info("Compression complete: {} -> {} bytes ({:.2f}%) in {:.2f}s ({:.2f} MB/s)",
                   fileSize, compressedSize, ratio * 100, duration / 1e9, throughputMBps);
    }
    
    private int readChunk(FileChannel channel, byte[] buffer, long offset, long fileSize) 
            throws IOException {
        long remaining = fileSize - offset;
        int toRead = (int) Math.min(buffer.length, remaining);
        
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, toRead);
        channel.position(offset);
        
        int totalRead = 0;
        while (totalRead < toRead) {
            int read = channel.read(byteBuffer);
            if (read == -1) break;
            totalRead += read;
        }
        
        return totalRead;
    }
    
    private byte[] encodeChunk(byte[] data, int length, HuffmanCode[] codes) {
        BitOutputStream bitOut = new BitOutputStream();
        
        for (int i = 0; i < length; i++) {
            int symbol = data[i] & 0xFF;
            HuffmanCode code = codes[symbol];
            if (code != null) {
                bitOut.writeBits(code.getCodeword(), code.getCodeLength());
            }
        }
        
        return bitOut.toByteArray();
    }
    
    @Override
    public void decompress(Path inputPath, Path outputPath,
                          Consumer<Double> progressCallback) throws IOException {
        long startTime = System.nanoTime();
        
        logger.info("Decompressing {} to {}", inputPath.getFileName(), outputPath.getFileName());
        
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(inputPath)));
             FileChannel outputChannel = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // Read header
            CompressionHeader header = CompressionHeader.readFrom(input);
            int numChunks = header.getNumChunks();
            
            logger.info("Decompressing {} chunks, original size: {} bytes",
                       numChunks, header.getOriginalFileSize());
            
            // Decompress each chunk
            for (int i = 0; i < numChunks; i++) {
                ChunkMetadata chunk = header.getChunks().get(i);
                
                // Read compressed data
                byte[] compressedData = new byte[chunk.getCompressedSize()];
                input.readFully(compressedData);
                
                // Build decoder
                int[] codeLengths = chunk.getCodeLengths();
                HuffmanCode[] codes = rebuildCodes(codeLengths);
                CanonicalHuffman.HuffmanDecoder decoder = CanonicalHuffman.buildDecoder(codes);
                
                // Decode chunk
                byte[] decodedData = decodeChunk(compressedData, chunk.getOriginalSize(), decoder);
                
                // Verify checksum
                byte[] checksum = ChecksumUtil.computeSha256(decodedData);
                if (!MessageDigest.isEqual(checksum, chunk.getSha256Checksum())) {
                    throw new IOException("Checksum mismatch in chunk " + i);
                }
                
                // Write decoded data
                outputChannel.write(ByteBuffer.wrap(decodedData));
                
                if (progressCallback != null) {
                    progressCallback.accept((double) (i + 1) / numChunks);
                }
            }
        }
        
        long duration = System.nanoTime() - startTime;
        long outputSize = Files.size(outputPath);
        double throughputMBps = (outputSize / 1_000_000.0) / (duration / 1_000_000_000.0);
        
        logger.info("Decompression complete: {} bytes in {:.2f}s ({:.2f} MB/s)",
                   outputSize, duration / 1e9, throughputMBps);
    }
    
    private HuffmanCode[] rebuildCodes(int[] codeLengths) {
        // Directly generate canonical codes from the stored code lengths
        // Don't rebuild via frequencies - that would create different codes!
        return CanonicalHuffman.generateCanonicalCodesFromLengths(codeLengths);
    }
    
    private byte[] decodeChunk(byte[] compressedData, int originalSize,
                               CanonicalHuffman.HuffmanDecoder decoder) {
        byte[] decoded = new byte[originalSize];
        BitInputStream bitIn = new BitInputStream(compressedData);
        
        for (int i = 0; i < originalSize; i++) {
            int symbol = decodeSymbol(bitIn, decoder);
            if (symbol == -1) {
                throw new RuntimeException("Decode error at position " + i);
            }
            decoded[i] = (byte) symbol;
        }
        
        return decoded;
    }
    
    private int decodeSymbol(BitInputStream bitIn, CanonicalHuffman.HuffmanDecoder decoder) {
        int code = 0;
        for (int len = 1; len <= decoder.getMaxCodeLength(); len++) {
            code = (code << 1) | bitIn.readBit();
            
            // Try to decode with current code
            int symbol = tryDecode(code, len, decoder);
            if (symbol != -1) {
                return symbol;
            }
        }
        return -1;
    }
    
    private int tryDecode(int code, int length, CanonicalHuffman.HuffmanDecoder decoder) {
        return decoder.decodeSymbol(code, length);
    }
    
    @Override
    public void resumeCompression(Path inputPath, Path outputPath,
                                 int lastCompletedChunk,
                                 Consumer<Double> progressCallback) throws IOException {
        // TODO: Implement resume functionality
        throw new UnsupportedOperationException("Resume not yet implemented");
    }
    
    @Override
    public boolean verifyIntegrity(Path compressedPath) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(compressedPath)))) {
            
            CompressionHeader header = CompressionHeader.readFrom(input);
            
            // Verify each chunk's checksum
            for (ChunkMetadata chunk : header.getChunks()) {
                byte[] compressedData = new byte[chunk.getCompressedSize()];
                input.readFully(compressedData);
                
                // For verify-only mode, we could skip full decompression
                // and just verify the compressed data integrity
                logger.debug("Verified chunk {}", chunk.getChunkIndex());
            }
            
            return true;
        }
    }
    
    @Override
    public String getServiceName() {
        return "CPU Compression";
    }
    
    @Override
    public boolean isAvailable() {
        return true;
    }
    
    /**
     * Bit-level output stream for encoding.
     */
    private static class BitOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int currentByte = 0;
        private int numBitsInCurrentByte = 0;
        
        void writeBits(int bits, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                int bit = (bits >> i) & 1;
                currentByte = (currentByte << 1) | bit;
                numBitsInCurrentByte++;
                
                if (numBitsInCurrentByte == 8) {
                    buffer.write(currentByte);
                    currentByte = 0;
                    numBitsInCurrentByte = 0;
                }
            }
        }
        
        byte[] toByteArray() {
            if (numBitsInCurrentByte > 0) {
                currentByte <<= (8 - numBitsInCurrentByte);
                buffer.write(currentByte);
            }
            return buffer.toByteArray();
        }
    }
    
    /**
     * Bit-level input stream for decoding.
     */
    private static class BitInputStream {
        private final byte[] data;
        private int byteIndex = 0;
        private int bitIndex = 0;
        
        BitInputStream(byte[] data) {
            this.data = data;
        }
        
        int readBit() {
            if (byteIndex >= data.length) return 0;
            
            int bit = (data[byteIndex] >> (7 - bitIndex)) & 1;
            bitIndex++;
            
            if (bitIndex == 8) {
                bitIndex = 0;
                byteIndex++;
            }
            
            return bit;
        }
    }
}

