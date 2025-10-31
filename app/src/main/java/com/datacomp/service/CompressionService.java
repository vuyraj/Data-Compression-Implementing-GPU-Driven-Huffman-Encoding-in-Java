package com.datacomp.service;

import com.datacomp.core.ChunkMetadata;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Service interface for compression operations.
 */
public interface CompressionService {
    
    /**
     * Compress a file.
     * 
     * @param inputPath Input file path
     * @param outputPath Output file path
     * @param progressCallback Callback for progress updates (0.0 to 1.0)
     * @throws IOException If I/O error occurs
     */
    void compress(Path inputPath, Path outputPath, 
                 Consumer<Double> progressCallback) throws IOException;
    
    /**
     * Decompress a file.
     * 
     * @param inputPath Compressed file path
     * @param outputPath Output file path
     * @param progressCallback Callback for progress updates (0.0 to 1.0)
     * @throws IOException If I/O error occurs
     */
    void decompress(Path inputPath, Path outputPath,
                   Consumer<Double> progressCallback) throws IOException;
    
    /**
     * Resume compression from a checkpoint.
     * 
     * @param inputPath Input file path
     * @param outputPath Partial output file path
     * @param lastCompletedChunk Last successfully compressed chunk index
     * @param progressCallback Callback for progress updates
     * @throws IOException If I/O error occurs
     */
    void resumeCompression(Path inputPath, Path outputPath,
                          int lastCompletedChunk,
                          Consumer<Double> progressCallback) throws IOException;
    
    /**
     * Verify compressed file integrity without full decompression.
     * 
     * @param compressedPath Compressed file path
     * @return true if all checksums match
     * @throws IOException If I/O error occurs
     */
    boolean verifyIntegrity(Path compressedPath) throws IOException;
    
    /**
     * Get service name.
     */
    String getServiceName();
    
    /**
     * Check if service is available.
     */
    boolean isAvailable();
}

