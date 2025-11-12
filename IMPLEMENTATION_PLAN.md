# GPU Huffman Encoding Implementation Plan
## Based on "Revisiting Huffman Coding" (arXiv:2010.10039v1)

### Paper's Key Innovation: Reduction-Based Encoding

#### Problem with Current Approach
1. **Prefix-Sum Method** (currently disabled in our code):
   - Calculate write position for each symbol using parallel prefix-sum
   - Move variable-length codes one-by-one
   - **Issue**: Low memory bandwidth utilization (~37 GB/s on V100)
   - Moving 1-2 bit codes wastes 87-93% of bandwidth

2. **Sequential CPU Encoding** (current implementation):
   - Fast due to branch prediction & caching
   - But cannot scale to GPU parallelism

#### Solution: Two-Phase Iterative Merge

**Phase 1: REDUCE-MERGE** (Coarse-grained parallelism)
```
Goal: Merge multiple codewords per thread until reaching word size

Input: Array of symbols [s0, s1, s2, ... s_{N-1}]
Step 1: Lookup codewords: (code_0, len_0), (code_1, len_1), ...
Step 2: Merge pairs iteratively r times:
  
  Iteration 1 (2→1):
    Thread 0: Merge (code_0, len_0) + (code_1, len_1) → (merged_0, len_0+len_1)
    Thread 1: Merge (code_2, len_2) + (code_3, len_3) → (merged_1, len_2+len_3)
    ...
    Result: N/2 merged codes
  
  Iteration 2 (4→1):
    Thread 0: Merge merged_0 + merged_1 → (merged_0', len_sum)
    Thread 2: Merge merged_2 + merged_3 → (merged_1', len_sum)
    ...
    Result: N/4 merged codes
  
  ... Continue for r iterations ...
  
  Result: N/2^r merged codes, each ~16-32 bits
```

**Phase 2: SHUFFLE-MERGE** (Fine-grained parallelism)
```
Goal: Coalesce merged codes into dense bitstream

Input: N/2^r merged codes (each ~20-30 bits avg)
Step: Iteratively merge with coalesced memory writes
  
  For each iteration s:
    - Group codes: [left_group] [right_group]
    - Calculate positions (no atomics needed!)
    - Batch move right_group to append after left_group
    - Use aligned, coalesced writes
  
  Result: Dense bitstream with maximum memory bandwidth
```

#### Mathematical Framework

**Parameters**:
- M = magnitude (log2 of chunk size), typically M=10 (1024 symbols/chunk)
- r = reduction factor (number of REDUCE-MERGE iterations)
- s = shuffle factor = M - r (number of SHUFFLE-MERGE iterations)
- N = 2^M (chunk size)
- n = 2^s (reduced size after REDUCE-MERGE)

**Determining r** (from average codeword length β̄):
```
Condition: floor(log2(β̄)) + r + 1 = log2(word_size)

Example:
- Average codeword = 1.027 bits
- floor(log2(1.027)) = 0
- Target word size = 32 bits (uint32)
- 0 + r + 1 = 5  =>  r = 4
- Result: 2^4 = 16 codes merged per thread
- Final merged length ≈ 16.4 bits (fits in uint32)
```

**Performance Optimization**:
From Table II in paper:
- M=10, r=3: **Best performance** (314.63 GB/s on V100)
- M=12, r=4: Lower performance (227.60 GB/s)
- M=10, r=2: Much worse (172.54 GB/s)

**Why this works**:
1. REDUCE-MERGE keeps threads busy (no idle threads moving 1-bit codes)
2. SHUFFLE-MERGE uses coalesced writes (100% memory efficiency)
3. No atomic operations (deterministic positions)
4. Scales with GPU memory bandwidth

---

### Implementation Strategy for TornadoVM

#### Challenge: TornadoVM Limitations
1. No cooperative groups (grid-wide synchronization)
2. No dynamic parallelism
3. Limited atomic operations support
4. No shuffle instructions (warp-level primitives)

#### Adapted Approach

**Stage 1: Parallel Histogram** ✅ (Already implemented)
- Use TornadoVM parallel reduction
- Already working well

**Stage 2: Parallel Codebook Construction** 🔄 (Needs improvement)
Current: Sequential CPU construction
Target: Parallel GPU construction (Algorithm 1 from paper)

Challenges:
- Requires grid-wide synchronization (use multiple kernel launches)
- Parallel merge needs careful implementation
- Leader-pointer updates need atomic operations

**Stage 3: Canonical Codebook** ✅ (Already implemented)
- Minor optimizations possible
- Current approach is acceptable

**Stage 4: Reduction-Based Encoding** 🆕 (Main implementation)

**TornadoVM Implementation Plan**:

```java
// Phase 1: REDUCE-MERGE (r=3 iterations for typical data)
private static void reduceM ergeKernel(
    byte[] input,           // Original data
    HuffmanCode[] codebook, // Lookup table
    int[] mergedCodes,      // Output: merged codewords
    int[] mergedLengths,    // Output: merged lengths
    int iteration,          // Current iteration (0 to r-1)
    int reductionFactor     // r value
) {
    int tid = TornadoMath.getGlobalId();
    int inputIndex = tid * (1 << (iteration + 1)); // 2^(i+1) stride
    
    // Lookup or load previous merged codes
    if (iteration == 0) {
        // First iteration: lookup from codebook
        int symbol1 = input[inputIndex] & 0xFF;
        int symbol2 = input[inputIndex + 1] & 0xFF;
        HuffmanCode code1 = codebook[symbol1];
        HuffmanCode code2 = codebook[symbol2];
        
        // Merge two codes
        mergedCodes[tid] = (code1.codeword << code2.length) | code2.codeword;
        mergedLengths[tid] = code1.length + code2.length;
    } else {
        // Subsequent iterations: merge previous results
        int prevTid1 = tid * 2;
        int prevTid2 = tid * 2 + 1;
        
        int code1 = mergedCodes[prevTid1];
        int len1 = mergedLengths[prevTid1];
        int code2 = mergedCodes[prevTid2];
        int len2 = mergedLengths[prevTid2];
        
        // Merge
        mergedCodes[tid] = (code1 << len2) | code2;
        mergedLengths[tid] = len1 + len2;
    }
}

// Phase 2: SHUFFLE-MERGE (s=7 iterations for M=10, r=3)
private static void shuffleMergeKernel(
    int[] mergedCodes,      // Input from REDUCE-MERGE
    int[] mergedLengths,
    byte[] output,          // Dense bitstream output
    int[] outputBitPos,     // Bit position tracker
    int iteration,
    int shuffleFactor
) {
    int tid = TornadoMath.getGlobalId();
    int stride = 1 << (iteration + 1);
    int leftIdx = (tid / stride) * stride * 2;
    int rightIdx = leftIdx + stride;
    
    // Calculate write position (deterministic, no atomics!)
    int leftBitPos = outputBitPos[leftIdx];
    int leftBitLen = mergedLengths[leftIdx];
    
    // Write right code after left code
    int writeBitPos = leftBitPos + leftBitLen;
    int writeBytePos = writeBitPos / 8;
    int writeBitOffset = writeBitPos % 8;
    
    int rightCode = mergedCodes[rightIdx];
    int rightLen = mergedLengths[rightIdx];
    
    // Pack bits into output (MSB-first)
    packBits(output, writeBytePos, writeBitOffset, rightCode, rightLen);
    
    // Update merged result
    if (tid % stride == 0) {
        mergedLengths[leftIdx] = leftBitLen + rightLen;
        outputBitPos[leftIdx] = leftBitPos;
    }
}
```

**Execution Flow**:
```java
public byte[] encodeChunkReductionBased(byte[] data, int length, HuffmanCode[] codes) {
    int M = 10;  // 1024 symbols per sub-chunk
    int r = 3;   // Reduction iterations
    int s = M - r; // 7 shuffle iterations
    
    int numSubChunks = (length + (1<<M) - 1) / (1<<M);
    
    // Allocate intermediate buffers
    int[] mergedCodes = new int[numSubChunks * (1<<M)];
    int[] mergedLengths = new int[numSubChunks * (1<<M)];
    int[] outputBitPos = new int[numSubChunks * (1<<M)];
    
    // Phase 1: REDUCE-MERGE (r iterations)
    for (int iter = 0; iter < r; iter++) {
        int numThreads = (1 << M) / (1 << (iter + 1));
        
        TaskGraph reduceTask = new TaskGraph("reduce_iter_" + iter)
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, 
                data, codes, mergedCodes, mergedLengths)
            .task("reduce", GpuCompressionService::reduceMergeKernel,
                data, codes, mergedCodes, mergedLengths, iter, r)
            .transferToHost(DataTransferMode.EVERY_EXECUTION,
                mergedCodes, mergedLengths);
        
        // Execute
        new TornadoExecutionPlan(reduceTask.snapshot())
            .withDevice(device)
            .execute();
    }
    
    // Phase 2: SHUFFLE-MERGE (s iterations)
    byte[] output = new byte[estimateOutputSize(mergedLengths)];
    
    for (int iter = 0; iter < s; iter++) {
        int numThreads = (1 << s) / (1 << (iter + 1));
        
        TaskGraph shuffleTask = new TaskGraph("shuffle_iter_" + iter)
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                mergedCodes, mergedLengths, outputBitPos)
            .task("shuffle", GpuCompressionService::shuffleMergeKernel,
                mergedCodes, mergedLengths, output, outputBitPos, iter, s)
            .transferToHost(DataTransferMode.EVERY_EXECUTION,
                output, mergedLengths, outputBitPos);
        
        new TornadoExecutionPlan(shuffleTask.snapshot())
            .withDevice(device)
            .execute();
    }
    
    return output;
}
```

---

### Expected Performance Improvement

**Current Performance** (CPU encoding):
- ~50-70 GB/s on multi-core CPU

**Target Performance** (GPU reduction-based):
- V100: 300+ GB/s (6× improvement)
- RTX 5000: 250+ GB/s (5× improvement)
- MX330 (2GB): 100-150 GB/s (2-3× improvement)

**Key Metrics to Measure**:
1. Memory bandwidth utilization (GB/s)
2. Encoding throughput (MB/s of input data)
3. Compression ratio (should be identical)
4. GPU utilization (%)

---

### Implementation Phases

**Phase 1: Basic Reduction-Merge** (1-2 days)
- Implement simple 2-to-1 merge kernel
- Test with small datasets
- Verify correctness

**Phase 2: Iterative Reduction** (2-3 days)
- Implement full r-iteration reduce-merge
- Optimize for different reduction factors
- Handle edge cases (non-power-of-2 sizes)

**Phase 3: Shuffle-Merge** (3-4 days)
- Implement coalesced batch moves
- Optimize memory access patterns
- Handle bit-packing efficiently

**Phase 4: Integration & Optimization** (2-3 days)
- Integrate with existing compression pipeline
- Tune parameters (M, r, s) for different datasets
- Add fallback for incompressible data

**Phase 5: Testing & Validation** (2-3 days)
- Test with all sample datasets
- Verify compression ratios
- Measure performance improvements
- Compare with paper's results

---

### Risks & Mitigation

**Risk 1**: TornadoVM doesn't support required operations
- **Mitigation**: Use multiple kernel launches instead of cooperative groups
- **Fallback**: Hybrid approach (GPU reduce-merge, CPU shuffle-merge)

**Risk 2**: Memory bandwidth not as high as paper
- **Mitigation**: Optimize for MX330's lower bandwidth
- **Target**: 80-120 GB/s (still 2× improvement)

**Risk 3**: Bit manipulation overhead in Java/TornadoVM
- **Mitigation**: Use int/long operations, avoid bit-by-bit
- **Optimization**: Vectorize where possible

**Risk 4**: Complex debugging on GPU
- **Mitigation**: Implement CPU reference version first
- **Tools**: Print intermediate results, validate each iteration

---

### Success Criteria

✅ **Minimum Viable Product**:
- Correct compression (bit-identical to current)
- 2× faster than current GPU encoding
- Handles all test datasets

✅ **Target Goals**:
- 3-4× faster than current encoding
- 100+ GB/s bandwidth on MX330
- Scales with chunk size

✅ **Stretch Goals**:
- 5-6× faster (match paper's results)
- 200+ GB/s bandwidth
- Support for 16-bit symbols (65536 codebook)

---

### Next Steps

1. **Read paper's supplementary code** (if available on GitHub)
2. **Implement basic reduce-merge kernel** in TornadoVM
3. **Test with synthetic data** (known codeword lengths)
4. **Iterate and optimize**
5. **Integrate with main compression pipeline**

Would you like me to start implementing the reduction-based encoding approach?
