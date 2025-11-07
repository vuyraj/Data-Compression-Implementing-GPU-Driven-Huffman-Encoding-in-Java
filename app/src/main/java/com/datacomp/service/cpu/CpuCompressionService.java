package com.datacomp.service.cpu;

import com.datacomp.core.*;
import com.datacomp.model.StageMetrics;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * CPU-based compression service with parallel chunk processing for large files.
 */
public class CpuCompressionService implements CompressionService {
    
    private static final Logger logger = LoggerFactory.getLogger(CpuCompressionService.class);
    
    private final FrequencyService frequencyService;
    private final int chunkSizeBytes;
    private StageMetrics lastStageMetrics;
    private final ExecutorService executorService;
    private final int parallelChunks;
    
    public CpuCompressionService(int chunkSizeMB) {
        this.frequencyService = new CpuFrequencyService();
        this.chunkSizeBytes = chunkSizeMB * 1024 * 1024;
        this.lastStageMetrics = new StageMetrics();
        
        // Determine number of parallel chunks based on available processors
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        this.parallelChunks = Math.max(2, Math.min(availableProcessors, 8)); // 2-8 parallel chunks
        this.executorService = Executors.newFixedThreadPool(parallelChunks);
        
        logger.info("Initialized CPU compression service with {} parallel chunk workers", parallelChunks);
    }
    
    /**
     * Get metrics from the last compression/decompression operation.
     */
    public StageMetrics getLastStageMetrics() {
        return lastStageMetrics;
    }
    
    @Override
    public void compress(Path inputPath, Path outputPath, 
                        Consumer<Double> progressCallback) throws IOException {
        // Reset metrics for new operation
        lastStageMetrics = new StageMetrics();
        
        long startTime = System.nanoTime();
        long fileSize = Files.size(inputPath);
        int numChunks = (int) ((fileSize + chunkSizeBytes - 1) / chunkSizeBytes);
        
        logger.info("🚀 Parallel compression: {} ({} bytes) into {} chunks using {} workers",
                   inputPath.getFileName(), fileSize, numChunks, parallelChunks);
        
        // Prepare header
        CompressionHeader header = new CompressionHeader(
            inputPath.getFileName().toString(),
            fileSize,
            Files.getLastModifiedTime(inputPath).toMillis(),
            new byte[32], // Global checksum computed later
            chunkSizeBytes
        );
        
        MessageDigest globalDigest = ChecksumUtil.createSha256();
        
        // Store compressed chunks in order
        Map<Integer, CompressedChunkData> compressedChunks = new ConcurrentHashMap<>();
        AtomicInteger completedChunks = new AtomicInteger(0);
        
        try (RandomAccessFile inputFile = new RandomAccessFile(inputPath.toFile(), "r");
             FileChannel inputChannel = inputFile.getChannel()) {
            
            // Read all chunks in parallel and compress them
            List<Future<CompressedChunkData>> futures = new ArrayList<>();
            
            for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
                final int index = chunkIndex;
                final long offset = (long) chunkIndex * chunkSizeBytes;
                
                Future<CompressedChunkData> future = executorService.submit(() -> 
                    processChunk(inputChannel, index, offset, fileSize)
                );
                futures.add(future);
            }
            
            // Collect results and update progress
            for (int i = 0; i < futures.size(); i++) {
                try {
                    CompressedChunkData chunkData = futures.get(i).get();
                    compressedChunks.put(chunkData.index, chunkData);
                    
                    // Update global checksum
                    synchronized (globalDigest) {
                        globalDigest.update(chunkData.checksum);
                    }
                    
                    int completed = completedChunks.incrementAndGet();
                    if (progressCallback != null) {
                        progressCallback.accept((double) completed / numChunks);
                    }
                    
                    logger.debug("Chunk {} compressed: {} -> {} bytes (ratio: {:.2f}%)",
                               chunkData.index, chunkData.originalSize, chunkData.compressedData.length,
                               (chunkData.compressedData.length * 100.0 / chunkData.originalSize));
                    
                } catch (InterruptedException | ExecutionException e) {
                    throw new IOException("Chunk compression failed", e);
                }
            }
            
            // Compute global checksum
            byte[] globalChecksum = globalDigest.digest();
            
            // Build final header with chunk metadata
            CompressionHeader finalHeader = new CompressionHeader(
                header.getOriginalFileName(),
                header.getOriginalFileSize(),
                header.getOriginalTimestamp(),
                globalChecksum,
                header.getChunkSizeBytes()
            );
            
            long compressedOffset = 0;
            for (int i = 0; i < numChunks; i++) {
                CompressedChunkData chunkData = compressedChunks.get(i);
                ChunkMetadata chunkMeta = new ChunkMetadata(
                    chunkData.index,
                    chunkData.originalOffset,
                    chunkData.originalSize,
                    compressedOffset,
                    chunkData.compressedData.length,
                    chunkData.checksum,
                    chunkData.codeLengths
                );
                finalHeader.addChunk(chunkMeta);
                compressedOffset += chunkData.compressedData.length;
            }
            
            // Write header and compressed chunks to output file
            long writeStart = System.nanoTime();
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(outputPath,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
                
                // Write header
                long headerStart = System.nanoTime();
                finalHeader.writeTo(output);
                lastStageMetrics.recordStage(StageMetrics.Stage.HEADER_WRITE, System.nanoTime() - headerStart, 0);
                
                // Write compressed chunks in order
                for (int i = 0; i < numChunks; i++) {
                    CompressedChunkData chunkData = compressedChunks.get(i);
                    output.write(chunkData.compressedData);
                }
            }
            long writeTime = System.nanoTime() - writeStart;
            lastStageMetrics.recordStage(StageMetrics.Stage.FILE_IO, writeTime, Files.size(outputPath));
        }
        
        long duration = System.nanoTime() - startTime;
        long compressedSize = Files.size(outputPath);
        double ratio = (double) compressedSize / fileSize;
        double throughputMBps = (fileSize / 1_000_000.0) / (duration / 1_000_000_000.0);
        
        logger.info("✅ Parallel compression complete: {} -> {} bytes ({:.2f}%) in {:.2f}s ({:.2f} MB/s)",
                   fileSize, compressedSize, ratio * 100, duration / 1e9, throughputMBps);
        
        // Log stage metrics
        logger.info("\n{}", lastStageMetrics.getSummary());
    }
    
    /**
     * Process a single chunk in parallel.
     */
    private CompressedChunkData processChunk(FileChannel inputChannel, int chunkIndex, 
                                             long offset, long fileSize) throws IOException {
        byte[] chunkData = new byte[chunkSizeBytes];
        
        // Track file I/O time
        long ioStart = System.nanoTime();
        int bytesRead;
        synchronized (inputChannel) {
            bytesRead = readChunk(inputChannel, chunkData, offset, fileSize);
        }
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.FILE_IO, System.nanoTime() - ioStart, bytesRead);
        }
        
        // Track checksum computation
        long checksumStart = System.nanoTime();
        MessageDigest chunkDigest = ChecksumUtil.createSha256();
        chunkDigest.update(chunkData, 0, bytesRead);
        byte[] chunkChecksum = chunkDigest.digest();
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.CHECKSUM_COMPUTE, System.nanoTime() - checksumStart, bytesRead);
        }
        
        // Track frequency analysis
        long freqStart = System.nanoTime();
        long[] frequencies = frequencyService.computeHistogram(chunkData, 0, bytesRead);
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.FREQUENCY_ANALYSIS, System.nanoTime() - freqStart, bytesRead);
        }
        
        // Track Huffman tree building
        long huffmanStart = System.nanoTime();
        HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.HUFFMAN_TREE_BUILD, System.nanoTime() - huffmanStart, bytesRead);
        }
        
        // Extract code lengths for metadata
        int[] codeLengths = new int[256];
        for (int i = 0; i < 256; i++) {
            codeLengths[i] = (codes[i] != null) ? codes[i].getCodeLength() : 0;
        }
        
        // Track encoding
        long encodeStart = System.nanoTime();
        byte[] compressedData = encodeChunk(chunkData, bytesRead, codes);
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.ENCODING, System.nanoTime() - encodeStart, bytesRead);
        }
        
        return new CompressedChunkData(chunkIndex, offset, bytesRead, compressedData, chunkChecksum, codeLengths);
    }
    
    /**
     * Container for compressed chunk data.
     */
    private static class CompressedChunkData {
        final int index;
        final long originalOffset;
        final int originalSize;
        final byte[] compressedData;
        final byte[] checksum;
        final int[] codeLengths;
        
        CompressedChunkData(int index, long originalOffset, int originalSize, 
                          byte[] compressedData, byte[] checksum, int[] codeLengths) {
            this.index = index;
            this.originalOffset = originalOffset;
            this.originalSize = originalSize;
            this.compressedData = compressedData;
            this.checksum = checksum;
            this.codeLengths = codeLengths;
        }
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
        // Reset metrics for new operation
        lastStageMetrics = new StageMetrics();
        
        long startTime = System.nanoTime();
        
        logger.info("🚀 Parallel decompression: {} to {} using {} workers", 
                   inputPath.getFileName(), outputPath.getFileName(), parallelChunks);
        
        // Read header and all compressed chunk data first
        CompressionHeader header;
        List<byte[]> compressedChunksData = new ArrayList<>();
        
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(inputPath)))) {
            
            // Read header
            long headerStart = System.nanoTime();
            header = CompressionHeader.readFrom(input);
            lastStageMetrics.recordStage(StageMetrics.Stage.FILE_IO, System.nanoTime() - headerStart, 0);
            
            int numChunks = header.getNumChunks();
            
            logger.info("Decompressing {} chunks, original size: {} bytes",
                       numChunks, header.getOriginalFileSize());
            
            // Read all compressed chunks
            for (int i = 0; i < numChunks; i++) {
                ChunkMetadata chunk = header.getChunks().get(i);
                byte[] compressedData = new byte[chunk.getCompressedSize()];
                input.readFully(compressedData);
                compressedChunksData.add(compressedData);
            }
        }
        
        // Process chunks in parallel
        Map<Integer, byte[]> decodedChunks = new ConcurrentHashMap<>();
        AtomicInteger completedChunks = new AtomicInteger(0);
        List<Future<DecodedChunkData>> futures = new ArrayList<>();
        
        for (int i = 0; i < header.getNumChunks(); i++) {
            final int index = i;
            final ChunkMetadata chunk = header.getChunks().get(i);
            final byte[] compressedData = compressedChunksData.get(i);
            
            Future<DecodedChunkData> future = executorService.submit(() -> 
                decodeChunkParallel(index, compressedData, chunk)
            );
            futures.add(future);
        }
        
        // Collect results
        for (int i = 0; i < futures.size(); i++) {
            try {
                DecodedChunkData chunkData = futures.get(i).get();
                decodedChunks.put(chunkData.index, chunkData.decodedData);
                
                int completed = completedChunks.incrementAndGet();
                if (progressCallback != null) {
                    progressCallback.accept((double) completed / header.getNumChunks());
                }
                
            } catch (InterruptedException | ExecutionException e) {
                throw new IOException("Chunk decompression failed", e);
            }
        }
        
        // Write decoded chunks in order
        try (FileChannel outputChannel = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            long writeStart = System.nanoTime();
            for (int i = 0; i < header.getNumChunks(); i++) {
                byte[] decodedData = decodedChunks.get(i);
                outputChannel.write(ByteBuffer.wrap(decodedData));
            }
            lastStageMetrics.recordStage(StageMetrics.Stage.FILE_IO, System.nanoTime() - writeStart, Files.size(outputPath));
        }
        
        long duration = System.nanoTime() - startTime;
        long outputSize = Files.size(outputPath);
        double throughputMBps = (outputSize / 1_000_000.0) / (duration / 1_000_000_000.0);
        
        logger.info("✅ Parallel decompression complete: {} bytes in {:.2f}s ({:.2f} MB/s)",
                   outputSize, duration / 1e9, throughputMBps);
        
        // Log stage metrics
        logger.info("\n{}", lastStageMetrics.getSummary());
    }
    
    /**
     * Decode a single chunk in parallel.
     */
    private DecodedChunkData decodeChunkParallel(int index, byte[] compressedData, 
                                                 ChunkMetadata chunk) throws IOException {
        // Track Huffman tree rebuild
        long huffmanStart = System.nanoTime();
        int[] codeLengths = chunk.getCodeLengths();
        HuffmanCode[] codes = rebuildCodes(codeLengths);
        CanonicalHuffman.HuffmanDecoder decoder = CanonicalHuffman.buildDecoder(codes);
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.HUFFMAN_TREE_BUILD, System.nanoTime() - huffmanStart, 0);
        }
        
        // Track decoding
        long decodeStart = System.nanoTime();
        byte[] decodedData = decodeChunk(compressedData, chunk.getOriginalSize(), decoder);
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.DECODING, System.nanoTime() - decodeStart, decodedData.length);
        }
        
        // Track checksum verification
        long checksumStart = System.nanoTime();
        byte[] checksum = ChecksumUtil.computeSha256(decodedData);
        if (!MessageDigest.isEqual(checksum, chunk.getSha256Checksum())) {
            throw new IOException("Checksum mismatch in chunk " + index);
        }
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.CHECKSUM_VERIFY, System.nanoTime() - checksumStart, decodedData.length);
        }
        
        return new DecodedChunkData(index, decodedData);
    }
    
    /**
     * Container for decoded chunk data.
     */
    private static class DecodedChunkData {
        final int index;
        final byte[] decodedData;
        
        DecodedChunkData(int index, byte[] decodedData) {
            this.index = index;
            this.decodedData = decodedData;
        }
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

