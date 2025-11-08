package com.datacomp.service.gpu;

import com.datacomp.core.*;
import com.datacomp.model.StageMetrics;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * GPU-accelerated compression service using TornadoVM with parallel chunk processing.
 * Falls back to CPU implementation if GPU is unavailable.
 */
public class GpuCompressionService implements CompressionService, AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(GpuCompressionService.class);
    
    private final FrequencyService frequencyService;
    private final CompressionService cpuFallback;
    private final boolean fallbackOnError;
    private final int chunkSizeBytes;
    private StageMetrics lastStageMetrics;
    private final ExecutorService executorService;
    private final int parallelChunks;
    
    public GpuCompressionService(int chunkSizeMB, boolean fallbackOnError) {
        this.fallbackOnError = fallbackOnError;
        this.chunkSizeBytes = chunkSizeMB * 1024 * 1024;
        this.cpuFallback = new CpuCompressionService(chunkSizeMB);
        this.lastStageMetrics = new StageMetrics();
        
        try {
            this.frequencyService = new GpuFrequencyService();
            if (frequencyService.isAvailable()) {
                // Calculate safe parallel chunks based on GPU memory
                this.parallelChunks = calculateSafeParallelChunks(chunkSizeMB);
                this.executorService = Executors.newFixedThreadPool(parallelChunks);
                
                logger.info("GPU compression service initialized: {} with {} parallel chunk workers",
                          frequencyService.getServiceName(), parallelChunks);
            } else {
                logger.warn("GPU not available, will use CPU fallback");
                this.parallelChunks = 1;
                this.executorService = Executors.newFixedThreadPool(1);
            }
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            logger.warn("TornadoVM runtime not available: {}", e.getMessage());
            throw new RuntimeException("GPU initialization failed - TornadoVM not properly configured", e);
        } catch (Exception e) {
            logger.error("Failed to initialize GPU service: {}", e.getMessage());
            throw new RuntimeException("GPU initialization failed", e);
        }
    }
    
    /**
     * Calculate safe number of parallel chunks based on GPU memory.
     * Formula: parallelChunks = (availableGPUMem * safetyFactor) / chunkMemoryUsage
     */
    private int calculateSafeParallelChunks(int chunkSizeMB) {
        if (!(frequencyService instanceof GpuFrequencyService)) {
            return 1; // CPU fallback
        }
        
        GpuFrequencyService gpuService = (GpuFrequencyService) frequencyService;
        long availableMemBytes = gpuService.getAvailableMemoryBytes();
        
        // Memory usage per chunk:
        // - Input data: chunkSizeMB
        // - Histogram: 256 * 4 bytes = 1KB (negligible)
        // - TornadoVM overhead: ~20% of input data
        // - Kernel compilation cache: ~50MB (one-time)
        long chunkMemUsage = (long)(chunkSizeMB * 1024 * 1024 * 1.2); // 20% overhead
        long reservedMem = 50L * 1024 * 1024; // Reserve 50MB for TornadoVM
        long usableMemory = Math.max(availableMemBytes - reservedMem, chunkMemUsage);
        
        int maxParallel = (int)(usableMemory / chunkMemUsage);
        
        // Apply safety limits:
        // - Minimum: 1 (sequential)
        // - Maximum: 4 (to avoid driver instability)
        // - Cap at available memory / chunk size
        int safeParallel = Math.max(1, Math.min(4, maxParallel));
        
        // Estimate total VRAM based on available memory (available is ~40% of total)
        long estimatedTotalVRAM = (availableMemBytes * 100) / 40;
        
        logger.info("🎮 GPU Memory Analysis:");
        logger.info("   Estimated Total VRAM: {} MB", estimatedTotalVRAM / (1024 * 1024));
        logger.info("   Safe Available Memory: {} MB", availableMemBytes / (1024 * 1024));
        logger.info("   Chunk Size: {} MB", chunkSizeMB);
        logger.info("   Memory per Chunk (with overhead): {} MB", chunkMemUsage / (1024 * 1024));
        logger.info("   Calculated Max Parallel: {}", maxParallel);
        logger.info("   Safe Parallel Chunks: {} (capped at 4 for stability)", safeParallel);
        
        return safeParallel;
    }
    
    /**
     * Get metrics from the last compression/decompression operation.
     */
    public StageMetrics getLastStageMetrics() {
        // If we fell back to CPU, get metrics from CPU service
        if (cpuFallback instanceof CpuCompressionService) {
            CpuCompressionService cpuService = (CpuCompressionService) cpuFallback;
            return cpuService.getLastStageMetrics();
        }
        return lastStageMetrics;
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
        // Reset metrics for new operation
        lastStageMetrics = new StageMetrics();
        
        long startTime = System.nanoTime();
        long fileSize = Files.size(inputPath);
        int numChunks = (int) ((fileSize + chunkSizeBytes - 1) / chunkSizeBytes);
        
        logger.info("🚀 GPU Parallel Compression: {} ({} bytes) into {} chunks using {} GPU workers",
                   inputPath.getFileName(), fileSize, numChunks, parallelChunks);
        
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
        
        // Store compressed chunks in order (parallel processing)
        Map<Integer, CompressedChunkData> compressedChunks = new ConcurrentHashMap<>();
        AtomicInteger completedChunks = new AtomicInteger(0);
        
        try (RandomAccessFile inputFile = new RandomAccessFile(inputPath.toFile(), "r");
             FileChannel inputChannel = inputFile.getChannel()) {
            
            // Process chunks in parallel using GPU
            List<Future<CompressedChunkData>> futures = new ArrayList<>();
            
            for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
                final int index = chunkIndex;
                final long offset = (long) chunkIndex * chunkSizeBytes;
                
                Future<CompressedChunkData> future = executorService.submit(() -> 
                    processChunkGpu(inputChannel, index, offset, fileSize)
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
                    
                    logger.debug("🎮 GPU Chunk {} done: {} -> {} bytes (ratio: {:.2f}%)",
                               chunkData.index, chunkData.originalSize, chunkData.compressedData.length,
                               (chunkData.compressedData.length * 100.0 / chunkData.originalSize));
                    
                } catch (InterruptedException | ExecutionException e) {
                    throw new IOException("GPU chunk compression failed", e);
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
                synchronized (lastStageMetrics) {
                    lastStageMetrics.recordStage(StageMetrics.Stage.HEADER_WRITE, System.nanoTime() - headerStart, 0);
                }
                
                // Write compressed chunks in order
                for (int i = 0; i < numChunks; i++) {
                    CompressedChunkData chunkData = compressedChunks.get(i);
                    output.write(chunkData.compressedData);
                }
            }
            long writeTime = System.nanoTime() - writeStart;
            synchronized (lastStageMetrics) {
                lastStageMetrics.recordStage(StageMetrics.Stage.FILE_IO, writeTime, Files.size(outputPath));
            }
            
            // Clear all large data structures to free memory immediately
            compressedChunks.clear();
        }
        
        long duration = System.nanoTime() - startTime;
        long compressedSize = Files.size(outputPath);
        double ratio = (double) compressedSize / fileSize;
        double throughputMBps = (fileSize / 1_000_000.0) / (duration / 1_000_000_000.0);
        
        logger.info("✅ GPU Parallel compression complete: {} -> {} bytes ({:.2f}%) in {:.2f}s ({:.2f} MB/s)",
                   fileSize, compressedSize, ratio * 100, duration / 1e9, throughputMBps);
        
        // Suggest garbage collection to free memory
        System.gc();
        logger.debug("🧹 Memory cleanup complete, suggested GC to free ~{} MB", 
                    (fileSize / 1_000_000));
        
        // Log stage metrics
        logger.info("\n{}", lastStageMetrics.getSummary());
    }
    
    /**
     * Process a single chunk in parallel with GPU acceleration.
     */
    private CompressedChunkData processChunkGpu(FileChannel inputChannel, int chunkIndex, 
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
        
        // 🎮 GPU frequency computation - THE KEY PARALLEL OPERATION!
        long gpuStartTime = System.nanoTime();
        long[] frequencies;
        try {
            frequencies = frequencyService.computeHistogram(chunkData, 0, bytesRead);
            long gpuTime = System.nanoTime() - gpuStartTime;
            synchronized (lastStageMetrics) {
                lastStageMetrics.recordStage(StageMetrics.Stage.FREQUENCY_ANALYSIS, gpuTime, bytesRead);
            }
            logger.debug("🎮 GPU histogram chunk {} done in {:.2f} ms", chunkIndex, gpuTime / 1_000_000.0);
        } catch (Exception e) {
            logger.warn("❌ GPU histogram failed for chunk {}: {}", chunkIndex, e.getMessage());
            throw new IOException("GPU processing failed", e);
        }
        
        // Huffman tree building
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
        
        // Encode chunk
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
    
    /**
     * Shutdown the executor service and release all resources.
     * This MUST be called when the service is no longer needed to prevent memory leaks.
     */
    @Override
    public void close() {
        logger.info("🛑 Shutting down GPU compression service with {} workers", parallelChunks);
        
        // Shutdown GPU executor
        executorService.shutdown();
        try {
            // Wait up to 30 seconds for tasks to complete
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("GPU executor did not terminate in time, forcing shutdown");
                executorService.shutdownNow();
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("GPU executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.error("GPU shutdown interrupted, forcing immediate shutdown");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Also shutdown CPU fallback service
        if (cpuFallback instanceof AutoCloseable) {
            try {
                ((AutoCloseable) cpuFallback).close();
            } catch (Exception e) {
                logger.error("Failed to close CPU fallback service: {}", e.getMessage());
            }
        }
        
        logger.info("✅ GPU compression service shutdown complete");
    }
}

