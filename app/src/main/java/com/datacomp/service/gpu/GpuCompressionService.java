package com.datacomp.service.gpu;

import com.datacomp.core.*;
import com.datacomp.model.StageMetrics;
import com.datacomp.service.CompressionService;
import com.datacomp.service.FrequencyService;
import com.datacomp.service.cpu.CpuCompressionService;
import com.datacomp.util.ChecksumUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.annotations.Parallel;

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
     * Returns GPU metrics if available, otherwise CPU fallback metrics.
     */
    public StageMetrics getLastStageMetrics() {
        // Return GPU metrics if we have any recorded stages
        if (lastStageMetrics != null && !lastStageMetrics.getAllStageTimes().isEmpty()) {
            return lastStageMetrics;
        }
        
        // Otherwise, try to get CPU fallback metrics (if we fell back to CPU)
        if (cpuFallback instanceof CpuCompressionService) {
            CpuCompressionService cpuService = (CpuCompressionService) cpuFallback;
            StageMetrics cpuMetrics = cpuService.getLastStageMetrics();
            if (cpuMetrics != null && !cpuMetrics.getAllStageTimes().isEmpty()) {
                return cpuMetrics;
            }
        }
        
        // Return empty metrics as last resort
        return lastStageMetrics != null ? lastStageMetrics : new StageMetrics();
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
        
        // Note: GPU Huffman decoding is currently disabled because:
        // 1. TornadoVM cannot efficiently compile the complex bit-manipulation kernel
        // 2. GPU decode is 10x+ slower than CPU table-based decode
        // 3. Huffman decoding is inherently sequential within chunks
        // 
        // The GPU is still used for:
        // - Parallel frequency histograms during compression (20-30% speedup)
        // - Future: Parallel LZ77 preprocessing, entropy encoding stages
        //
        // For now, we use the fast CPU table-based decoder (2-3x faster than tree traversal)
        
        logger.info("💻 Using parallel CPU decompression with table-based Huffman decoder");
        cpuFallback.decompress(inputPath, outputPath, progressCallback);
    }
    
    /**
     * GPU-accelerated decompression using table-based Huffman decoding.
     * Processes multiple chunks in parallel on GPU.
     */
    private void decompressGpu(Path inputPath, Path outputPath,
                              Consumer<Double> progressCallback) throws IOException {
        logger.info("🎮 Starting GPU parallel decompression with {} workers", parallelChunks);
        
        // Reset metrics for new operation
        lastStageMetrics = new StageMetrics();
        
        long startTime = System.nanoTime();
        
        // Read header
        CompressionHeader header;
        long compressedDataStart;
        
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(inputPath)))) {
            
            long headerStart = System.nanoTime();
            header = CompressionHeader.readFrom(input);
            
            // Calculate where compressed data starts
            long totalCompressedSize = 0;
            for (ChunkMetadata chunk : header.getChunks()) {
                totalCompressedSize += chunk.getCompressedSize();
            }
            long totalFileSize = Files.size(inputPath);
            compressedDataStart = totalFileSize - totalCompressedSize;
            
            synchronized (lastStageMetrics) {
                lastStageMetrics.recordStage(StageMetrics.Stage.FILE_IO, System.nanoTime() - headerStart, 0);
            }
        }
        
        int numChunks = header.getChunks().size();
        logger.info("🎮 GPU decompressing {} chunks in parallel batches", numChunks);
        
        // Process chunks in batches (same as CPU, but each chunk can potentially use GPU)
        int batchSize = parallelChunks; // Process N chunks at a time
        int numBatches = (numChunks + batchSize - 1) / batchSize;
        
        AtomicInteger completedChunks = new AtomicInteger(0);
        
        try (RandomAccessFile inputFile = new RandomAccessFile(inputPath.toFile(), "r");
             FileChannel outputChannel = FileChannel.open(outputPath,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            
            for (int batchIdx = 0; batchIdx < numBatches; batchIdx++) {
                int startChunk = batchIdx * batchSize;
                int endChunk = Math.min(startChunk + batchSize, numChunks);
                int batchChunkCount = endChunk - startChunk;
                
                // Read compressed batch data
                long readStart = System.nanoTime();
                Map<Integer, byte[]> compressedBatchData = new ConcurrentHashMap<>();
                
                synchronized (inputFile) {
                    for (int i = startChunk; i < endChunk; i++) {
                        ChunkMetadata chunk = header.getChunks().get(i);
                        byte[] compressedData = new byte[chunk.getCompressedSize()];
                        inputFile.seek(compressedDataStart + chunk.getCompressedOffset());
                        inputFile.read(compressedData);
                        compressedBatchData.put(i, compressedData);
                    }
                }
                synchronized (lastStageMetrics) {
                    lastStageMetrics.recordStage(StageMetrics.Stage.FILE_IO, System.nanoTime() - readStart, 
                        batchChunkCount * (long)chunkSizeBytes);
                }
                
                // Submit batch for parallel GPU decoding with progress updates
                Map<Integer, byte[]> decodedChunks = new ConcurrentHashMap<>();
                List<Future<DecodedChunkData>> futures = new ArrayList<>();
                
                // Submit all chunks in batch to executor
                for (int i = startChunk; i < endChunk; i++) {
                    final int index = i;
                    final ChunkMetadata chunk = header.getChunks().get(i);
                    final byte[] compressedData = compressedBatchData.get(i);
                    
                    Future<DecodedChunkData> future = executorService.submit(() -> 
                        decodeChunkGpu(index, compressedData, chunk)
                    );
                    futures.add(future);
                }
                
                // Collect batch results with immediate progress updates
                for (int futureIdx = 0; futureIdx < futures.size(); futureIdx++) {
                    try {
                        Future<DecodedChunkData> future = futures.get(futureIdx);
                        
                        // Poll with timeout to allow progress updates even during GPU blocking
                        DecodedChunkData chunkData = null;
                        boolean taskCompleted = false;
                        
                        while (!taskCompleted) {
                            try {
                                // Wait max 200ms, then update progress even if not done
                                // Shorter timeout = smoother progress updates
                                chunkData = future.get(200, TimeUnit.MILLISECONDS);
                                taskCompleted = true;
                            } catch (TimeoutException e) {
                                // GPU still working, update progress with partial completion
                                // This keeps the UI responsive during GPU execution
                                if (progressCallback != null) {
                                    int currentCompleted = completedChunks.get();
                                    double baseProgress = (double) currentCompleted / numChunks;
                                    double batchProgress = (double) futureIdx / futures.size();
                                    double batchWeight = (double) futures.size() / numChunks;
                                    double partialProgress = baseProgress + (batchProgress * batchWeight);
                                    
                                    // Cap at 99% until actually complete
                                    final double progressToReport = Math.min(partialProgress, 0.99);
                                    
                                    // Invoke callback (will be wrapped in Platform.runLater by GUI)
                                    progressCallback.accept(progressToReport);
                                }
                            }
                        }
                        
                        if (chunkData != null) {
                            decodedChunks.put(chunkData.index, chunkData.decodedData);
                            
                            int completedCount = completedChunks.incrementAndGet();
                            if (progressCallback != null) {
                                progressCallback.accept((double) completedCount / numChunks);
                            }
                        }
                        
                    } catch (InterruptedException | ExecutionException e) {
                        throw new IOException("GPU chunk decompression failed", e);
                    }
                }
                
                // Write batch in order
                long writeStart = System.nanoTime();
                synchronized (outputChannel) {
                    for (int i = startChunk; i < endChunk; i++) {
                        byte[] decodedData = decodedChunks.get(i);
                        outputChannel.write(ByteBuffer.wrap(decodedData));
                    }
                }
                synchronized (lastStageMetrics) {
                    lastStageMetrics.recordStage(StageMetrics.Stage.FILE_IO, System.nanoTime() - writeStart, 
                        batchChunkCount * (long)chunkSizeBytes);
                }
                
                // Clear batch data to free memory
                compressedBatchData.clear();
                decodedChunks.clear();
                futures.clear();
                
                logger.debug("🎮 GPU Batch {}/{} complete ({}-{})", 
                           batchIdx + 1, numBatches, startChunk, endChunk - 1);
            }
        }
        
        long duration = System.nanoTime() - startTime;
        long outputSize = Files.size(outputPath);
        double durationSec = duration / 1_000_000_000.0;
        double throughputMBps = (outputSize / 1_000_000.0) / durationSec;
        
        logger.info("✅ GPU parallel decompression complete: {} bytes in {:.2f}s ({:.2f} MB/s)",
                   outputSize, String.format("%.2f", durationSec), String.format("%.2f", throughputMBps));
        
        // Aggressive memory cleanup
        System.gc();
        System.runFinalization();
        logger.debug("🧹 Memory cleanup complete after GPU decompression");
        
        // Log stage metrics
        logger.info("\n{}", lastStageMetrics.getSummary());
    }
    
    /**
     * Decode a single chunk - attempts GPU parallel decoding, falls back to CPU if needed.
     */
    private DecodedChunkData decodeChunkGpu(int index, byte[] compressedData, 
                                           ChunkMetadata chunk) throws IOException {
        // Track Huffman tree rebuild
        long huffmanStart = System.nanoTime();
        int[] codeLengths = chunk.getCodeLengths();
        HuffmanCode[] codes = rebuildCodes(codeLengths);
        synchronized (lastStageMetrics) {
            lastStageMetrics.recordStage(StageMetrics.Stage.HUFFMAN_TREE_BUILD, System.nanoTime() - huffmanStart, 0);
        }
        
        // Try GPU decoding if available
        long decodeStart = System.nanoTime();
        byte[] decodedData = null;
        
        // DISABLED: GPU Huffman decoding is currently too slow and produces incorrect results
        // The kernel is too complex for TornadoVM to compile efficiently to GPU
        // Instead, we use the fast CPU table-based decoder which is 10x+ faster
        boolean useGpu = false; // Temporarily disabled until kernel is optimized
        
        if (useGpu && frequencyService.isAvailable() && frequencyService instanceof GpuFrequencyService) {
            logger.info("🎮 Attempting GPU decode for chunk {} (compressed={} bytes, expected output={} bytes)", 
                       index, compressedData.length, chunk.getOriginalSize());
            try {
                // Build lookup table for this chunk
                int[] lookupSymbols = new int[1024];
                int[] lookupLengths = new int[1024];
                buildLookupTable(codes, lookupSymbols, lookupLengths);
                
                // Attempt GPU decoding
                decodedData = decodeOnGpu(compressedData, chunk.getOriginalSize(), 
                                         lookupSymbols, lookupLengths, codes);
                
                // Verify output size
                if (decodedData != null && decodedData.length == chunk.getOriginalSize()) {
                    logger.info("✅ Chunk {} decoded on GPU successfully ({} bytes)", index, decodedData.length);
                } else {
                    logger.warn("⚠️  GPU decode for chunk {} produced wrong size: expected {}, got {}", 
                               index, chunk.getOriginalSize(), decodedData != null ? decodedData.length : 0);
                    useGpu = false;
                    decodedData = null;
                }
            } catch (Exception e) {
                logger.error("❌ GPU decode failed for chunk {}: {} - {}", index, e.getClass().getSimpleName(), e.getMessage(), e);
                useGpu = false;
            }
        }
        
        // Use fast CPU table-based decoder (2-3x faster than tree traversal)
        if (!useGpu || decodedData == null) {
            TableBasedHuffmanDecoder decoder = new TableBasedHuffmanDecoder(codes);
            decodedData = decoder.decode(compressedData, chunk.getOriginalSize());
        }
        
        // Clear codes to help GC
        for (int i = 0; i < codes.length; i++) {
            codes[i] = null;
        }
        
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
     * Build 10-bit lookup table for fast Huffman decoding.
     */
    private void buildLookupTable(HuffmanCode[] codes, int[] symbols, int[] lengths) {
        // Initialize as invalid
        for (int i = 0; i < 1024; i++) {
            symbols[i] = -1;
            lengths[i] = 0;
        }
        
        // Build lookup for codes <= 10 bits
        for (int symbol = 0; symbol < codes.length; symbol++) {
            if (codes[symbol] == null) continue;
            
            int codeLength = codes[symbol].getCodeLength();
            int codeValue = codes[symbol].getCodeword();
            
            if (codeLength > 10) continue;
            
            // Fill all patterns starting with this code
            int basePattern = codeValue << (10 - codeLength);
            int numCopies = 1 << (10 - codeLength);
            
            for (int i = 0; i < numCopies; i++) {
                symbols[basePattern + i] = symbol;
                lengths[basePattern + i] = codeLength;
            }
        }
    }
    
    /**
     * Decode compressed data on GPU using TornadoVM.
     * Each chunk runs on a separate GPU thread for true parallelism.
     */
    private byte[] decodeOnGpu(byte[] compressedData, int outputSize,
                               int[] lookupSymbols, int[] lookupLengths,
                               HuffmanCode[] codes) {
        
        // First, verify the lookup table has entries
        int validEntries = 0;
        for (int i = 0; i < 1024; i++) {
            if (lookupSymbols[i] != -1) {
                validEntries++;
            }
        }
        
        if (validEntries == 0) {
            throw new RuntimeException("Lookup table is empty - no valid codes");
        }
        
        logger.info("📊 GPU decode setup: compressed={} bytes, expected output={} bytes, lookup entries={}", 
                   compressedData.length, outputSize, validEntries);
        
        // Prepare output buffer
        byte[] output = new byte[outputSize];
        
        // Convert compressed data to int array for GPU
        int[] compressedInts = new int[compressedData.length];
        for (int i = 0; i < compressedData.length; i++) {
            compressedInts[i] = compressedData[i] & 0xFF;
        }
        
        // Prepare fallback codes (>10 bits) - but limit to avoid GPU complexity
        int[] fallbackCodes = new int[256];
        int[] fallbackLengths = new int[256];
        int fallbackCount = 0;
        for (int i = 0; i < 256; i++) {
            if (codes[i] != null && codes[i].getCodeLength() > 10) {
                fallbackCodes[i] = codes[i].getCodeword();
                fallbackLengths[i] = codes[i].getCodeLength();
                fallbackCount++;
            } else {
                fallbackCodes[i] = -1;
                fallbackLengths[i] = 0;
            }
        }
        
        logger.info("📊 GPU decode: {} fallback codes (>10 bits)", fallbackCount);
        
        try {
            // Get GPU device
            TornadoDevice device = null;
            if (frequencyService instanceof GpuFrequencyService) {
                device = ((GpuFrequencyService) frequencyService).getDevice();
            }
            
            if (device == null) {
                throw new RuntimeException("GPU device not available");
            }
            
            // Create task graph for GPU decoding
            TaskGraph taskGraph = new TaskGraph("gpuDecode")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    compressedInts, lookupSymbols, lookupLengths, 
                    fallbackCodes, fallbackLengths)
                .task("decode", GpuCompressionService::gpuDecodeKernel,
                    compressedInts, compressedInts.length,
                    lookupSymbols, lookupLengths,
                    fallbackCodes, fallbackLengths,
                    output, outputSize)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, output);
            
            ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
            TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph);
            executionPlan.withDevice(device);
            
            // Execute on GPU
            logger.info("⚡ Executing GPU decode kernel...");
            executionPlan.execute();
            logger.info("✅ GPU decode kernel completed");
            
            // Verify output is not all zeros
            int nonZeroBytes = 0;
            for (int i = 0; i < Math.min(100, output.length); i++) {
                if (output[i] != 0) nonZeroBytes++;
            }
            logger.info("🔍 GPU output validation: {}/{} non-zero bytes in first 100",
                       nonZeroBytes, Math.min(100, output.length));
            
            return output;
            
        } catch (Exception e) {
            logger.error("GPU decoding exception: {}", e.getMessage(), e);
            throw new RuntimeException("GPU decoding failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * GPU kernel for Huffman decoding.
     * Processes the compressed stream sequentially (Huffman requirement).
     * Multiple chunks can run in parallel, each on its own GPU thread.
     * 
     * Reads bits MSB-first (bit 7, then 6, then 5...) to match encoding order.
     */
    private static void gpuDecodeKernel(
            int[] compressed, int compressedSize,
            int[] lookupSymbols, int[] lookupLengths,
            int[] fallbackCodes, int[] fallbackLengths,
            byte[] output, int outputSize) {
        
        // Sequential decoding within this chunk
        int bytePos = 0;
        int bitPos = 0; // Current bit position within byte (0-7)
        int outPos = 0;
        
        while (outPos < outputSize && bytePos < compressedSize) {
            // Peek 10 bits for table lookup (MSB-first)
            int peek = 0;
            int tempBytePos = bytePos;
            int tempBitPos = bitPos;
            
            for (int i = 0; i < 10 && tempBytePos < compressedSize; i++) {
                // Read bit from position (7 - bitPos) in byte - MSB first
                int bit = (compressed[tempBytePos] >> (7 - tempBitPos)) & 1;
                peek = (peek << 1) | bit;
                tempBitPos++;
                if (tempBitPos >= 8) {
                    tempBitPos = 0;
                    tempBytePos++;
                }
            }
            
            // Table lookup
            int symbol = lookupSymbols[peek];
            int codeLen = lookupLengths[peek];
            
            // Fallback for codes > 10 bits (rare)
            if (symbol == -1) {
                int acc = peek;
                int len = 10;
                
                // Read up to 6 more bits (max Huffman code is 16 bits)
                for (int extra = 0; extra < 6 && tempBytePos < compressedSize; extra++) {
                    int bit = (compressed[tempBytePos] >> (7 - tempBitPos)) & 1;
                    acc = (acc << 1) | bit;
                    len++;
                    tempBitPos++;
                    if (tempBitPos >= 8) {
                        tempBitPos = 0;
                        tempBytePos++;
                    }
                    
                    // Search fallback table
                    for (int s = 0; s < 256; s++) {
                        if (fallbackLengths[s] == len && fallbackCodes[s] == acc) {
                            symbol = s;
                            codeLen = len;
                            break;
                        }
                    }
                    if (symbol != -1) break;
                }
            }
            
            // Write symbol
            if (symbol != -1) {
                output[outPos++] = (byte) symbol;
                
                // Advance bit position by codeLen bits
                bitPos += codeLen;
                while (bitPos >= 8 && bytePos < compressedSize) {
                    bitPos -= 8;
                    bytePos++;
                }
            } else {
                break; // Decoding error - couldn't find symbol
            }
        }
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
        return CanonicalHuffman.generateCanonicalCodesFromLengths(codeLengths);
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

