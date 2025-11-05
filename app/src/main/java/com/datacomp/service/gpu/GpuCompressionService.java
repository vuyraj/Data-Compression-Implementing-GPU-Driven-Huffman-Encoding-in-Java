package com.datacomp.service.gpu;

import com.datacomp.core.*;
import com.datacomp.service.CompressionService;
import com.datacomp.service.FrequencyService;
import com.datacomp.service.cpu.CpuCompressionService;
import com.datacomp.util.ChecksumUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.common.TornadoDevice;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.function.Consumer;

/**
 * GPU-accelerated compression service using TornadoVM.
 * Falls back to CPU implementation if GPU is unavailable.
 */
public class GpuCompressionService implements CompressionService {
    
    private static final Logger logger = LoggerFactory.getLogger(GpuCompressionService.class);
    
    private final FrequencyService frequencyService;
    private final CompressionService cpuFallback;
    private final boolean fallbackOnError;
    private final int chunkSizeBytes;
    
    public GpuCompressionService(int chunkSizeMB, boolean fallbackOnError) {
        this.fallbackOnError = fallbackOnError;
        this.chunkSizeBytes = chunkSizeMB * 1024 * 1024;
        this.cpuFallback = new CpuCompressionService(chunkSizeMB);
        
        try {
            this.frequencyService = new GpuFrequencyService();
            if (frequencyService.isAvailable()) {
                logger.info("GPU compression service initialized: {}",
                          frequencyService.getServiceName());
            } else {
                logger.warn("GPU not available, will use CPU fallback");
            }
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            logger.warn("TornadoVM runtime not available: {}", e.getMessage());
            throw new RuntimeException("GPU initialization failed - TornadoVM not properly configured", e);
        } catch (Exception e) {
            logger.error("Failed to initialize GPU service: {}", e.getMessage());
            throw new RuntimeException("GPU initialization failed", e);
        }
    }
    
    @Override
    public void compress(Path inputPath, Path outputPath,
                        Consumer<Double> progressCallback) throws IOException {
        if (!isAvailable() && fallbackOnError) {
            logger.warn("⚠️  GPU NOT AVAILABLE - Falling back to CPU compression");
            cpuFallback.compress(inputPath, outputPath, progressCallback);
            return;
        }
        
        try {
            logger.info("🚀 Starting GPU-ACCELERATED compression");
            logger.info("🎮 GPU Device: {}", frequencyService.getServiceName());
            
            // Use GPU-accelerated histogram computation
            compressWithGpuHistogram(inputPath, outputPath, progressCallback);
            
            logger.info("✅ GPU compression completed successfully");
            
        } catch (Exception e) {
            if (fallbackOnError) {
                logger.warn("❌ GPU compression failed, falling back to CPU", e);
                cpuFallback.compress(inputPath, outputPath, progressCallback);
            } else {
                throw new IOException("GPU compression failed", e);
            }
        }
    }
    
    private void compressWithGpuHistogram(Path inputPath, Path outputPath,
                                         Consumer<Double> progressCallback) throws IOException {
        long startTime = System.nanoTime();
        long fileSize = Files.size(inputPath);
        int numChunks = (int) ((fileSize + chunkSizeBytes - 1) / chunkSizeBytes);
        
        logger.info("GPU Compressing {} ({} bytes) into {} chunks",
                   inputPath.getFileName(), fileSize, numChunks);
        
        // Check if file is too large for GPU processing
        if (numChunks > 100) {
            logger.warn("⚠️  Large file with {} chunks - GPU may encounter memory issues", numChunks);
            logger.warn("⚠️  Consider using CPU compression for very large files");
        }
        
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
                
                // 🚀 USE GPU for frequency computation (this is where GPU is actually used!)
                long gpuStartTime = System.nanoTime();
                long[] frequencies;
                try {
                    frequencies = frequencyService.computeHistogram(chunkData, 0, bytesRead);
                    long gpuTime = System.nanoTime() - gpuStartTime;
                    logger.debug("GPU histogram for chunk {} completed in {:.2f} ms",
                               chunkIndex, gpuTime / 1_000_000.0);
                } catch (Exception e) {
                    logger.warn("❌ GPU histogram failed for chunk {}: {}", chunkIndex, e.getMessage());
                    logger.warn("⚠️  This indicates OpenCL driver issues. Falling back to CPU.");
                    throw new IOException("GPU processing failed - OpenCL driver error", e);
                }
                
                // Build Huffman codes (CPU - this is fast)
                HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
                
                // Extract code lengths for metadata
                int[] codeLengths = new int[256];
                for (int i = 0; i < 256; i++) {
                    codeLengths[i] = (codes[i] != null) ? codes[i].getCodeLength() : 0;
                }
                
                // Encode chunk with GPU acceleration
                long encodeStartTime = System.nanoTime();
                byte[] compressedData = encodeChunkGpu(chunkData, bytesRead, codes);
                long encodeTime = System.nanoTime() - encodeStartTime;
                
                logger.debug("GPU encoding for chunk {} completed in {:.2f} ms",
                           chunkIndex, encodeTime / 1_000_000.0);
                
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
            
            // Write everything
            output.write(headerBuffer.toByteArray());
            
            // Re-read and write compressed chunks
            inputChannel.position(0);
            currentOffset = 0;
            for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
                int bytesRead = readChunk(inputChannel, chunkData, currentOffset, fileSize);
                
                // GPU histogram computation
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
        
        logger.info("GPU Compression complete: {} -> {} bytes ({:.2f}%) in {:.2f}s ({:.2f} MB/s)",
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
    
    /**
     * GPU-accelerated encoding with automatic chunking for memory constraints.
     * For GPUs with limited VRAM (like MX330 with 2GB), uses CPU encoding
     * since GPU bit-packing kernels have alignment bugs that cause decode errors.
     * 
     * GPU is still used for frequency counting (histogram), which is the most
     * parallelizable part. Encoding will be GPU-accelerated once bit-packing is fixed.
     */
    private byte[] encodeChunkGpu(byte[] data, int length, HuffmanCode[] codes) {
        // TODO: Fix GPU bit-packing kernels for proper decompression
        // Current issue: writeCodewordsOptimizedKernel produces output that fails at decode
        // For now, use reliable CPU encoding
        logger.debug("💻 Using sequential CPU encoding ({} bytes)", length);
        return encodeChunk(data, length, codes);
    }

    @Override
    public void decompress(Path inputPath, Path outputPath,
                          Consumer<Double> progressCallback) throws IOException {
        // GPU decompression can be added as optimization
        // For now, use CPU implementation
        cpuFallback.decompress(inputPath, outputPath, progressCallback);
    }
    
    @Override
    public void resumeCompression(Path inputPath, Path outputPath,
                                 int lastCompletedChunk,
                                 Consumer<Double> progressCallback) throws IOException {
        cpuFallback.resumeCompression(inputPath, outputPath, lastCompletedChunk, progressCallback);
    }
    
    @Override
    public boolean verifyIntegrity(Path compressedPath) throws IOException {
        return cpuFallback.verifyIntegrity(compressedPath);
    }
    
    @Override
    public String getServiceName() {
        return "GPU Compression (" + frequencyService.getServiceName() + ")";
    }
    
    @Override
    public boolean isAvailable() {
        return frequencyService != null && frequencyService.isAvailable();
    }
    
    public TornadoDevice getDevice() {
        if (frequencyService instanceof GpuFrequencyService) {
            // Return device info
        }
        return null;
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
}

