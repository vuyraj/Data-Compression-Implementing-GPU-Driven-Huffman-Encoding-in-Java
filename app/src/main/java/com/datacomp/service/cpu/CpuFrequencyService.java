package com.datacomp.service.cpu;

import com.datacomp.service.FrequencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * CPU-based frequency histogram computation with parallel processing.
 */
public class CpuFrequencyService implements FrequencyService {
    
    private static final Logger logger = LoggerFactory.getLogger(CpuFrequencyService.class);
    private static final int PARALLEL_THRESHOLD = 64 * 1024; // 64KB
    
    private final ForkJoinPool pool;
    
    public CpuFrequencyService() {
        this(ForkJoinPool.commonPool());
    }
    
    public CpuFrequencyService(ForkJoinPool pool) {
        this.pool = pool;
    }
    
    @Override
    public long[] computeHistogram(byte[] data, int offset, int length) {
        if (length < PARALLEL_THRESHOLD) {
            return computeHistogramSequential(data, offset, length);
        } else {
            return pool.invoke(new HistogramTask(data, offset, length));
        }
    }
    
    private long[] computeHistogramSequential(byte[] data, int offset, int length) {
        long[] frequencies = new long[256];
        int end = offset + length;
        
        for (int i = offset; i < end; i++) {
            frequencies[data[i] & 0xFF]++;
        }
        
        return frequencies;
    }
    
    @Override
    public String getServiceName() {
        return "CPU (Multi-threaded)";
    }
    
    @Override
    public boolean isAvailable() {
        return true;
    }
    
    /**
     * Fork/Join task for parallel histogram computation.
     */
    private static class HistogramTask extends RecursiveTask<long[]> {
        private final byte[] data;
        private final int offset;
        private final int length;
        
        HistogramTask(byte[] data, int offset, int length) {
            this.data = data;
            this.offset = offset;
            this.length = length;
        }
        
        @Override
        protected long[] compute() {
            if (length < PARALLEL_THRESHOLD) {
                return computeSequential();
            }
            
            int mid = length / 2;
            HistogramTask left = new HistogramTask(data, offset, mid);
            HistogramTask right = new HistogramTask(data, offset + mid, length - mid);
            
            left.fork();
            long[] rightResult = right.compute();
            long[] leftResult = left.join();
            
            // Merge results
            for (int i = 0; i < 256; i++) {
                leftResult[i] += rightResult[i];
            }
            
            return leftResult;
        }
        
        private long[] computeSequential() {
            long[] frequencies = new long[256];
            int end = offset + length;
            
            for (int i = offset; i < end; i++) {
                frequencies[data[i] & 0xFF]++;
            }
            
            return frequencies;
        }
    }
}

