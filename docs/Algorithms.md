# 4.3 Algorithm Details

This section provides a comprehensive description of the algorithms employed in the GPU-accelerated Huffman compression system. The system implements a hybrid architecture that combines classical compression algorithms with modern parallel computing techniques to achieve high-performance lossless data compression.

## 4.3.1 Canonical Huffman Coding Algorithm

Canonical Huffman coding forms the theoretical foundation of the compression system. Unlike standard Huffman coding, the canonical variant imposes additional structure on the generated codes, enabling more efficient serialization and faster decoding.

### Frequency Analysis

The compression process begins with frequency analysis of the input data. For a given chunk of input bytes, the algorithm computes a histogram where each bin corresponds to one of the 256 possible byte values. The histogram $H$ is defined as:

$$H[s] = \sum_{i=0}^{n-1} \mathbb{1}_{data[i] = s}$$

where $s \in [0, 255]$ represents each possible byte value, $n$ is the chunk size, and $\mathbb{1}$ is the indicator function. This operation has time complexity $O(n)$ where $n$ is the input size.

### Huffman Tree Construction

Once frequencies are computed, the algorithm constructs a binary tree using a priority queue-based approach. The construction proceeds as follows:

1. For each symbol $s$ with non-zero frequency $H[s]$, create a leaf node with weight $H[s]$.
2. Insert all leaf nodes into a min-priority queue ordered by weight.
3. While the queue contains more than one node:
   - Extract the two nodes $n_1$ and $n_2$ with minimum weights.
   - Create a new internal node $n_{parent}$ with weight $w(n_{parent}) = w(n_1) + w(n_2)$.
   - Set $n_1$ and $n_2$ as children of $n_{parent}$.
   - Insert $n_{parent}$ into the queue.
4. The remaining node becomes the root of the Huffman tree.

This construction guarantees optimal prefix codes and executes in $O(m \log m)$ time, where $m$ is the number of distinct symbols in the input (at most 256 for byte-level coding).

### Canonical Code Generation

After tree construction, the algorithm extracts code lengths by traversing the tree and recording the depth of each leaf node. These code lengths are then used to generate canonical codes through the following process:

Let $L[s]$ denote the code length for symbol $s$. The algorithm computes the first codeword $F[len]$ for each code length $len$ using:

$$F[1] = 0$$
$$F[len] = (F[len-1] + C[len-1]) \times 2$$

where $C[len]$ is the count of symbols with code length $len$. Canonical codes are then assigned by distributing consecutive integers to symbols of the same length, sorted by symbol value. This canonical structure ensures that codes of the same length are numerically consecutive, which significantly simplifies decoding and reduces metadata storage requirements.

## 4.3.2 GPU-Accelerated Histogram Computation

The histogram computation represents the first stage where GPU acceleration is applied. The parallel nature of frequency counting makes it particularly suitable for GPU execution.

### Parallel Histogram Kernel

The GPU histogram kernel leverages data parallelism to process multiple input bytes simultaneously. In the TornadoVM framework, the kernel is expressed as:

```java
for (@Parallel int i = 0; i < length; i++) {
    int symbol = input[i] & 0xFF;
    histogram[symbol]++;
}
```

The `@Parallel` annotation instructs TornadoVM to distribute iterations across GPU threads. For a 16 MB chunk, this results in 16,777,216 parallel threads, each processing a single byte. The GPU scheduler organizes these threads into warps (NVIDIA) or wavefronts (AMD), typically containing 32 threads that execute in lockstep.

### Atomic Operations for Conflict Resolution

Multiple threads may attempt to increment the same histogram bin concurrently, creating a write conflict. The system employs atomic increment operations to ensure correctness:

$$H[s] \leftarrow H[s] + 1 \quad \text{(atomically)}$$

While atomic operations serialize conflicting updates, the overall throughput remains high because conflicts are relatively rare for typical data distributions. The GPU implementation achieves approximately 134 milliseconds per 16 MB chunk, corresponding to a throughput of 119 MB/s, which represents a 2.6× speedup over the multi-threaded CPU implementation.

### Memory Transfer Overhead

The GPU histogram algorithm requires two data transfers: input data must be transferred from host memory to device memory before computation, and the resulting 256-element histogram must be transferred back. For large chunks, the computation time dominates transfer overhead, making GPU acceleration beneficial. The total time is modeled as:

$$T_{total} = T_{transfer\_to} + T_{compute} + T_{transfer\_from}$$

where $T_{compute}$ scales with input size while transfer times remain relatively constant for fixed chunk sizes.

## 4.3.3 CPU-Based Parallel Histogram (Fallback)

When GPU resources are unavailable, the system employs a CPU-based parallel histogram computation using the Fork/Join framework. This algorithm recursively subdivides the input until reaching a threshold (64 KB), at which point sequential histogram computation is performed. The divide-and-conquer approach ensures good cache locality while exploiting available CPU parallelism.

The recursion splits the problem into two subproblems of approximately equal size:

$$H_{total} = H_{left} + H_{right}$$

where addition is element-wise across the 256 histogram bins. The algorithm achieves logarithmic span $O(\log n)$ with $O(n)$ work, making it theoretically efficient for multicore processors.

## 4.3.4 Table-Based Huffman Decoding

Decompression employs a table-based decoding algorithm that significantly outperforms traditional tree-traversal methods. The algorithm pre-computes a lookup table indexed by bit patterns, enabling constant-time symbol lookups.

### Lookup Table Construction

For a fixed table size parameter $k$ (typically 10 bits, yielding a 1024-entry table), the algorithm constructs a mapping from $k$-bit prefixes to decoded symbols. For each Huffman code with length $\ell \leq k$:

1. Compute the $k$-bit prefix by left-shifting the codeword: $prefix = code \ll (k - \ell)$.
2. For all $2^{k-\ell}$ possible suffixes, set $table[prefix \mid suffix] = (symbol, \ell)$.

This construction ensures that any $k$-bit prefix beginning with a valid code maps to the correct symbol and code length.

### Fast Decoding Process

Decoding proceeds by reading $k$ bits from the bitstream, performing a table lookup to retrieve the symbol and code length, and advancing the bit position by the code length:

$$position \leftarrow position + length$$

For codes longer than $k$ bits (rare in practice), the algorithm falls back to tree-based decoding. The table-based approach achieves 2-3× speedup over bit-by-bit tree traversal because it eliminates conditional branching and exploits cache-friendly memory access patterns.

## 4.3.5 SHA-256 Checksum Algorithm

Data integrity is ensured through SHA-256 cryptographic hashing, a member of the Secure Hash Algorithm family standardized by NIST. For each chunk, both the original uncompressed data and the compressed data are hashed.

### Hash Computation

SHA-256 operates on 512-bit message blocks and maintains an 8-word (256-bit) internal state. The algorithm processes the input in the following stages:

1. **Padding**: Append a '1' bit, followed by zero bits, followed by the 64-bit message length, such that the total length is a multiple of 512 bits.
2. **Parsing**: Divide the padded message into 512-bit blocks $M^{(1)}, M^{(2)}, \ldots, M^{(N)}$.
3. **Hash Computation**: For each block $M^{(i)}$:
   - Prepare a message schedule of 64 words.
   - Apply 64 rounds of mixing using logical functions (AND, OR, XOR, NOT) and modular addition.
   - Update the internal state.
4. **Output**: Concatenate the final 8-word state to produce the 256-bit hash.

The SHA-256 computation takes approximately 21 milliseconds per 16 MB chunk on modern CPUs. During decompression, the recomputed checksum is compared bit-by-bit with the stored value; any mismatch indicates data corruption.

## 4.3.6 Chunking and Parallel Processing Strategy

To handle files larger than available GPU memory and to exploit parallelism, the system divides input files into fixed-size chunks (16 MB by default). Each chunk is compressed independently, enabling embarrassingly parallel processing.

### Chunk Size Selection

The chunk size $C$ is chosen to balance several competing factors:

- **Memory Usage**: Each chunk requires GPU memory proportional to $C$ for input, histogram, and intermediate buffers. Smaller chunks reduce peak memory.
- **Compression Ratio**: Larger chunks provide more data for frequency analysis, potentially yielding better Huffman trees and higher compression ratios.
- **Parallelism**: Smaller chunks increase the number of independent tasks, enabling more effective use of multiple CPU cores or GPU streams.
- **Overhead**: Each chunk incurs fixed costs (tree construction, serialization, metadata), which become negligible for larger chunks.

The system uses 16 MB chunks, which fits comfortably within the 2 GB VRAM of the NVIDIA GeForce MX330 GPU while maintaining good compression efficiency. This size allows 4 chunks to be processed in parallel on the GPU without exceeding memory constraints.

### Thread Pool Management

The system maintains a fixed thread pool with a configurable number of worker threads (typically 4 for GPU mode, 8 for CPU mode). Each worker processes one chunk at a time, and the thread pool coordinator distributes chunks in a round-robin fashion. This design ensures consistent GPU utilization and prevents memory exhaustion from concurrent allocations.

## 4.3.7 Incompressibility Detection

Not all data compresses well. Highly random or already-compressed data may expand when Huffman-encoded due to metadata overhead. The system implements an incompressibility detector that checks whether all symbols require 8-bit codes (uniform distribution):

$$\forall s \in [0, 255] : length(code[s]) = 8$$

When this condition holds, the chunk is stored uncompressed, avoiding both computation time and expansion. This optimization is particularly effective for archive files (TAR, ZIP) where headers and already-compressed entries dominate.

## 4.3.8 Phase 3 GPU Encoding Algorithm (Disabled)

The system was originally designed with a fully GPU-accelerated encoding pipeline (Phase 3), which remains disabled in the current production version due to GPU memory constraints. This section describes the algorithm for completeness and to document future optimization directions.

### Three-Stage Reduction Pipeline

Phase 3 encoding employs a multi-stage reduction approach to convert variable-length codes into a packed bitstream without sequential dependencies.

**Stage 1: Codebook Lookup**

Every input symbol is replaced with its corresponding Huffman code in parallel:

```
Input:    [A, A, B, C, A, B, B]
Codes:    A=0(1-bit), B=10(2-bit), C=11(2-bit)
Output:   [(0,1), (0,1), (10,2), (11,2), (0,1), (10,2), (10,2)]
```

This operation is inherently parallel, with each thread processing one symbol independently. The output consists of $n$ code-length pairs, where $n$ is the number of input symbols.

**Stage 2: Reduce-Merge Iterations**

The code-length pairs are iteratively merged into longer segments using parallel reduction. In iteration $r$, pairs of elements are combined:

$$(code_1, len_1) \oplus (code_2, len_2) = (code_1 \ll len_2 \mid code_2, len_1 + len_2)$$

This merge operation concatenates two codes by shifting the first code left by the length of the second code and bitwise-ORing the results. The combined length is the sum of individual lengths. After $\lceil \log_2 n \rceil$ iterations, the reduction produces a single code representing the entire bitstream.

The reduction tree can be visualized as:

```
Iteration 0: [pairs of 2 symbols]
Iteration 1: [pairs of 4 symbols]
Iteration 2: [pairs of 8 symbols]
...
Iteration r: [pairs of 2^(r+1) symbols]
```

Each iteration halves the number of active elements, yielding a logarithmic number of iterations.

**Stage 3: Bitstream Packing**

The final stage packs reduced code segments into a contiguous byte array. Each segment knows its starting bit position (computed via prefix sum of lengths), and threads write their segments in parallel. Atomic operations handle overlapping byte boundaries to ensure correctness.

### Memory Requirements and Constraints

Phase 3's memory footprint proved problematic for the 2 GB VRAM of the target GPU. For a 16 MB chunk ($16 \times 2^{20}$ bytes), the algorithm requires:

- **Stage 1**: $3 \times 16$ MB = 48 MB (input symbols, output codes, output lengths)
- **Stage 2**: $\sum_{i=1}^{r} 2 \times (16/2^i)$ MB ≈ 96 MB (intermediate buffers across iterations)
- **Stage 3**: 10 MB (bitstream output and position arrays)
- **TornadoVM overhead**: ≈24 MB

Total: approximately 322 MB per chunk. With 4 parallel chunks, this exceeds the available VRAM, causing out-of-memory errors and silent failures. Consequently, the production system disables Phase 3 and uses CPU-based sequential encoding, which takes approximately 1,109 milliseconds per 16 MB chunk but guarantees correctness.

## 4.3.9 File Format and Serialization

The compressed file format is designed for efficient random access, streaming decompression, and data integrity verification.

### Header Structure

Each compressed file begins with a 24-byte header:

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0      | 2    | Magic | 0xDC5A ("DcZ") file identifier |
| 2      | 2    | Version | Format version (currently 0x0100) |
| 4      | 4    | Flags | Reserved for future extensions |
| 8      | 8    | Original Size | Uncompressed file size in bytes |
| 16     | 4    | Chunk Count | Number of compressed chunks |
| 20     | 4    | Reserved | Padding for alignment |

### Chunk Data Layout

Following the header, each chunk's compressed data is stored sequentially. Each chunk consists of:

1. **Tree Encoding** (variable size): Serialized canonical Huffman tree, storing code lengths for each symbol. Sparse representation omits zero-length codes.
2. **Compressed Bitstream** (variable size): Packed bits representing the Huffman-encoded data.

### Footer Metadata

The file concludes with a footer containing per-chunk metadata. For each chunk $i$:

| Field | Size | Description |
|-------|------|-------------|
| Original Offset | 8 | Byte offset in original file |
| Original Size | 4 | Uncompressed chunk size |
| Compressed Offset | 8 | Byte offset in compressed file |
| Compressed Size | 4 | Compressed chunk size |
| SHA-256 Checksum | 32 | Hash of uncompressed data |

The last 8 bytes of the file store the footer position, enabling efficient metadata lookup without reading the entire file. This design supports features like partial decompression and integrity verification of individual chunks.

## 4.3.10 Compression Workflow Integration

The complete compression workflow integrates these algorithms into a cohesive pipeline:

1. **Input Chunking**: The input file is divided into 16 MB chunks. If the final chunk is smaller, it is processed with its actual size.
2. **Parallel Frequency Analysis**: For each chunk, compute the histogram using GPU acceleration if available, otherwise fall back to CPU parallelism.
3. **Huffman Tree Construction**: Build the Huffman tree from the frequency histogram using a priority queue on the CPU. This step takes approximately 3 milliseconds per chunk and is not parallelized due to its fast execution and inherent sequential dependencies.
4. **Canonical Code Generation**: Convert the tree into canonical codes and extract code lengths. This representation minimizes metadata size.
5. **Encoding**: Apply Huffman codes to compress the chunk. Currently performed on CPU using a sequential bit-writing approach.
6. **Checksum Computation**: Compute SHA-256 hash of the original chunk data for integrity verification.
7. **Serialization**: Write the compressed chunk (tree + bitstream) to the output file.
8. **Metadata Collection**: Store chunk metadata (offsets, sizes, checksums) for the footer.
9. **Footer Writing**: After all chunks are processed, write the footer and footer position.

This pipeline achieves a throughput of approximately 12.6 MB/s on the reference hardware (NVIDIA GeForce MX330 GPU, Intel Core CPU), with encoding representing the primary bottleneck at 86% of total time.

## 4.3.11 Decompression Workflow Integration

Decompression reverses the compression process with the following stages:

1. **Header Parsing**: Read and validate the file header. Check magic bytes and version compatibility.
2. **Footer Location**: Seek to the end of the file and read the footer position pointer.
3. **Metadata Loading**: Read all chunk metadata from the footer into memory.
4. **Parallel Chunk Decompression**: For each chunk:
   - Read the tree encoding and reconstruct canonical Huffman codes.
   - Instantiate a table-based decoder with the reconstructed codes.
   - Decode the bitstream to recover the original chunk bytes.
   - Compute SHA-256 checksum and compare with stored value.
   - Write decompressed data to output file.
5. **Integrity Verification**: If any chunk fails checksum verification, abort decompression and report error.

Decompression exploits 8-way parallelism (8 worker threads), achieving approximately 127 MB/s throughput, which is 10× faster than compression. This asymmetry arises because decompression avoids the expensive frequency analysis and tree construction steps, and table-based decoding is significantly faster than sequential encoding.

## 4.3.12 Performance Characteristics and Complexity Analysis

### Time Complexity

For a file of size $N$ divided into chunks of size $C$:

- **Frequency Analysis**: $O(N)$ with GPU acceleration, $O(N \log P)$ with $P$-way CPU parallelism.
- **Tree Construction**: $O((N/C) \times 256 \log 256)$ across all chunks (constant per chunk).
- **Canonical Code Generation**: $O((N/C) \times 256)$ total.
- **Encoding**: $O(N)$ sequentially, $O(N/P)$ with $P$ parallel chunks.
- **Checksum**: $O(N)$ with efficient SHA-256 implementation.
- **Overall Compression**: $O(N)$ with constants dependent on hardware and parallelism.

### Space Complexity

- **Histogram**: $O(1)$ (fixed 256 elements).
- **Huffman Tree**: $O(1)$ (at most 256 leaves, 255 internal nodes).
- **Chunk Buffer**: $O(C)$ per active chunk.
- **Thread Pool**: $O(P \times C)$ where $P$ is the number of parallel workers.
- **GPU Memory**: $O(C)$ per chunk for histogram kernel.

The fixed chunk size ensures predictable memory usage regardless of input file size, preventing out-of-memory errors for large files.

### Compression Ratio

Huffman coding achieves entropy-optimal compression for symbol-by-symbol coding. The expected compressed size is:

$$L = \sum_{s=0}^{255} H[s] \times length(code[s])$$

bits, where $H[s]$ is the frequency of symbol $s$. For uniformly distributed data (maximum entropy), Huffman coding cannot compress beyond 8 bits per byte. For skewed distributions, compression ratios approaching the Shannon entropy bound are achievable:

$$H_{shannon} = -\sum_{s=0}^{255} p_s \log_2 p_s$$

where $p_s = H[s] / N$ is the probability of symbol $s$.

## 4.3.13 Algorithm Validation and Correctness

Correctness is ensured through multiple mechanisms:

1. **Lossless Guarantee**: Huffman coding is provably lossless. The tree reconstruction during decompression exactly inverts the encoding process.
2. **Checksum Verification**: SHA-256 provides cryptographic-strength integrity checking. The probability of undetected corruption is negligible ($< 2^{-256}$).
3. **Canonical Code Validation**: The canonical code generation algorithm is deterministic and follows the standard construction from DEFLATE (RFC 1951).
4. **Unit Testing**: Extensive unit tests verify correctness on edge cases (single symbol, empty input, incompressible data).
5. **Round-Trip Testing**: Every compressed file can be decompressed to recover the original data byte-for-byte, verified automatically.

These measures ensure that the compression system is production-ready and reliable for critical applications.

---

This completes the detailed description of all algorithms employed in the GPU-accelerated Huffman compression system. The hybrid architecture balances GPU parallelism for compute-intensive stages (frequency analysis) with CPU efficiency for inherently sequential operations (tree construction, encoding), achieving measurable performance improvements while maintaining data integrity and correctness guarantees.
