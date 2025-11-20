# Phase 3 GPU Encoding Algorithm - Deep Dive

**Author:** Expert Analysis - Low-Level Programming & Information Theory  
**Date:** November 13, 2025  
**Topic:** GPU-Accelerated Huffman Encoding via Reduction-Based Pipeline

---

## 📋 Table of Contents
1. [Algorithm Overview](#algorithm-overview)
2. [Mathematical Foundation](#mathematical-foundation)
3. [Stage 1: GPU Codebook Lookup](#stage-1-gpu-codebook-lookup)
4. [Stage 2: GPU Reduce-Merge Iterations](#stage-2-gpu-reduce-merge-iterations)
5. [Stage 3: GPU Bitstream Packing](#stage-3-gpu-bitstream-packing)
6. [Complete Example Walkthrough](#complete-example-walkthrough)
7. [GPU Implementation Details](#gpu-implementation-details)
8. [Memory Layout & Optimization](#memory-layout--optimization)
9. [Why This Is Hard](#why-this-is-hard)

---

## 🎯 Algorithm Overview

### The Core Problem

**Huffman Encoding:** Given a sequence of symbols and their variable-length codes, produce a packed bitstream.

**Example:**
```
Input:  "AAABBC"
Codes:  A=0, B=10, C=11

Sequential encoding:
A → 0
A → 0  
A → 0
B → 10
B → 10
C → 11

Output bitstream: 000101011 (9 bits)
```

**Why Sequential is Easy (CPU):**
```java
for (byte symbol : input) {
    HuffmanCode code = lookup[symbol];
    writeBits(code.bits, code.length);  // Append to stream
}
```
- Simple loop, inherently sequential
- Output bit position depends on ALL previous codes
- Natural for CPU, terrible for GPU

**Why Parallel is Hard (GPU):**
- **Data dependency:** Bit position for symbol[i] depends on lengths of ALL symbols[0..i-1]
- Can't directly parallelize without solving the dependency chain
- Need reduction algorithm to compute prefix sums efficiently

### Phase 3 Solution: Parallel Reduction Pipeline

```
Input: 16M symbols → Output: Packed bitstream

Pipeline Stages:
┌──────────────────────────────────────────────────────────┐
│ Stage 1: GPU Codebook Lookup (PARALLEL)                 │
│ • Each GPU thread processes one symbol                   │
│ • Lookup Huffman code and length                         │
│ • Result: codes[16M], lengths[16M]                       │
└──────────────────────────────────────────────────────────┘
                         ↓
┌──────────────────────────────────────────────────────────┐
│ Stage 2: GPU Reduce-Merge (LOG N ITERATIONS)            │
│ • Merge adjacent pairs in parallel                       │
│ • Compute cumulative bit positions                       │
│ • Iteration 1: 16M → 8M pairs                           │
│ • Iteration 2: 8M → 4M pairs                            │
│ • Iteration 3: 4M → 2M pairs                            │
│ • ... continue until manageable size                     │
│ • Result: Hierarchical position tree                     │
└──────────────────────────────────────────────────────────┘
                         ↓
┌──────────────────────────────────────────────────────────┐
│ Stage 3: GPU Bitstream Packing (PARALLEL)               │
│ • Each thread knows its absolute bit position            │
│ • Write variable-length codes to output                  │
│ • Handle byte boundaries and bit shifting                │
│ • Result: Compressed byte array                          │
└──────────────────────────────────────────────────────────┘
```

**Key Insight:** Use **parallel prefix sum** (scan) algorithm to compute bit positions, then parallel write!

---

## 📐 Mathematical Foundation

### Information Theory Basics

**Entropy:** Minimum average bits per symbol
```
H(X) = -Σ p(x) log₂ p(x)
```

**Huffman Property:** Optimal prefix-free code achieves H(X) ≤ L̄ < H(X) + 1
- L̄ = average code length
- Codes satisfy: shorter code → higher frequency

**Variable-Length Codes:**
```
Symbol | Frequency | Code   | Length
───────┼───────────┼────────┼────────
  A    |   0.5     |   0    |   1
  B    |   0.25    |   10   |   2
  C    |   0.125   |  110   |   3
  D    |   0.125   |  111   |   3
```

**Bitstream Packing Challenge:**
```
Symbols: A B A C D A B A
Codes:   0 10 0 110 111 0 10 0

Sequential output:
Bit position:  0  1 2 3 4 5 6 7 8 9 10 11 12 13
Output:        0 |1 0|0|1 1 0|1 1 1|0|1  0|0
               └A┘└B─┘└A┘└─C─┘└─D─┘└A┘└B─┘└A┘

How do we compute bit position for each symbol IN PARALLEL?
```

### Parallel Prefix Sum (Scan)

**Problem:** Given array [a₀, a₁, a₂, ..., aₙ], compute:
```
output[i] = a₀ + a₁ + a₂ + ... + aᵢ
```

**Sequential:** O(n) time, trivial
```
sum = 0
for i in 0..n:
    sum += a[i]
    output[i] = sum
```

**Parallel:** O(log n) time using reduction tree!

**Example:** Compute prefix sum of [1, 2, 3, 4, 5, 6, 7, 8]

```
Level 0 (Input):
[1] [2] [3] [4] [5] [6] [7] [8]

Level 1 (Add pairs):
[1, 3] [3, 7] [5, 11] [7, 15]
 └─┬─┘  └─┬─┘  └──┬──┘ └──┬──┘
   1+2    3+4     5+6     7+8

Level 2 (Add pairs of pairs):
[1, 3, 6, 10] [5, 11, 18, 26]
    └────┬────┘      └────┬────┘
     (1+2)+(3+4)    (5+6)+(7+8)

Level 3 (Final merge):
[1, 3, 6, 10, 15, 21, 28, 36]
```

**GPU Advantage:** Each level processes N/2 pairs in parallel → O(log N) depth!

### Application to Huffman Encoding

**Input:** Symbol lengths [L₀, L₁, L₂, ..., Lₙ]
```
Example: [1, 2, 1, 3, 3, 1, 2, 1] (for A B A C D A B A)
```

**Compute prefix sum:** Bit positions [P₀, P₁, P₂, ..., Pₙ]
```
P₀ = 0           (A starts at bit 0)
P₁ = 0 + 1 = 1   (B starts at bit 1)
P₂ = 1 + 2 = 3   (A starts at bit 3)
P₃ = 3 + 1 = 4   (C starts at bit 4)
P₄ = 4 + 3 = 7   (D starts at bit 7)
P₅ = 7 + 3 = 10  (A starts at bit 10)
P₆ = 10 + 1 = 11 (B starts at bit 11)
P₇ = 11 + 2 = 13 (A starts at bit 13)
```

**Now parallel write:**
```java
// Each GPU thread i:
bitPosition = prefixSum[i];
code = codes[i];
length = lengths[i];
writeBitsToOutput(bitPosition, code, length);  // PARALLEL!
```

**This is the core insight that makes Phase 3 possible!**

---

## 🔍 Stage 1: GPU Codebook Lookup

### Algorithm

**Input:**
- `input[N]`: byte array of symbols (N = 16,777,216 for 16MB chunk)
- `huffmanCodes[256]`: precomputed Huffman codebook

**Output:**
- `codes[N]`: Huffman code bits for each symbol
- `lengths[N]`: Code length for each symbol

**Pseudocode:**
```java
@Parallel
for (int i = 0; i < N; i++) {
    byte symbol = input[i];
    HuffmanCode hc = huffmanCodes[symbol];
    codes[i] = hc.bits;
    lengths[i] = hc.length;
}
```

### GPU Kernel Implementation

```java
private static void lookupKernel(
    byte[] symbols,        // Input: 16M bytes
    int[] codeBits,        // Codebook: 256 ints (bits)
    byte[] codeLengths,    // Codebook: 256 bytes (lengths)
    int[] outputCodes,     // Output: 16M ints
    byte[] outputLengths,  // Output: 16M bytes
    int N
) {
    for (@Parallel int i = 0; i < N; i++) {
        int symbol = symbols[i] & 0xFF;  // Convert to unsigned
        outputCodes[i] = codeBits[symbol];
        outputLengths[i] = codeLengths[symbol];
    }
}
```

### Memory Access Pattern

**Coalesced Memory Access:**
```
GPU Warp (32 threads):

Thread 0: symbols[0]  → codes[0],  lengths[0]
Thread 1: symbols[1]  → codes[1],  lengths[1]
Thread 2: symbols[2]  → codes[2],  lengths[2]
...
Thread 31: symbols[31] → codes[31], lengths[31]

All threads access consecutive memory → optimal bandwidth!
```

**Memory Transactions:**
- Input read: 16 MB sequential (good cache locality)
- Codebook read: 256 entries × 32 threads = 8 KB (fits in L1 cache)
- Output write: 64 MB + 16 MB sequential (coalesced writes)

### Performance Analysis

**GPU (MX330 - 640 CUDA cores):**
```
Parallelism: 16,777,216 operations
Threads per block: 256
Blocks: 65,536
Warps per block: 8

Memory bandwidth: ~48 GB/s
Data transfer: 96 MB (64 + 16 + 16)
Transfer time: ~2 ms

Compute: 16M operations ÷ 640 cores = ~26K ops/core
At 1.5 GHz: ~17 cycles per op → ~0.3 ms

Total: ~2.3 ms
```

**CPU (8 cores):**
```
Sequential: 16M lookups
Per core: 2M lookups
At 2.5 GHz: ~20 cycles per lookup
Time: (2M × 20) ÷ 2.5 GHz = 16 ms

Total: ~16 ms (7× slower than GPU)
```

### Example

**Input:** `[A, B, A, C]`  
**Codebook:**
```
A: bits=0b0,   length=1
B: bits=0b10,  length=2
C: bits=0b110, length=3
```

**After Stage 1:**
```
codes[]:   [0b0,   0b10,  0b0,   0b110]
lengths[]: [1,     2,     1,     3]
```

**Key:** All 4 symbols processed simultaneously on GPU!

---

## 🔄 Stage 2: GPU Reduce-Merge Iterations

### The Challenge

We have `lengths[]` array. Need to compute prefix sum (bit positions) in parallel.

**Naive approach won't work:**
```java
// WRONG - sequential dependency!
positions[0] = 0;
for (int i = 1; i < N; i++) {
    positions[i] = positions[i-1] + lengths[i-1];  // Depends on previous!
}
```

**Solution:** Hierarchical parallel reduction (tree-based algorithm)

### Algorithm: Up-Sweep Phase

**Build a binary tree of partial sums**

**Input:** `lengths[8] = [1, 2, 1, 3, 3, 1, 2, 1]`

**Iteration 0 (Leaf level):**
```
Pairs: [1,2] [1,3] [3,1] [2,1]
       └─┬─┘ └─┬─┘ └─┬─┘ └─┬─┘
         3     4     4     3
```

**Iteration 1:**
```
Pairs of pairs: [3,4] [4,3]
                └─┬─┘ └─┬─┘
                  7     7
```

**Iteration 2:**
```
Final: [7,7]
       └─┬─┘
        14  (total bits)
```

**GPU Implementation:**
```java
// Iteration k: merge pairs at distance 2^k
int stride = 1 << k;  // 1, 2, 4, 8, ...

@Parallel
for (int i = 0; i < N; i += 2*stride) {
    int left = sums[i + stride - 1];
    int right = sums[i + 2*stride - 1];
    sums[i + 2*stride - 1] = left + right;
}
```

### Algorithm: Down-Sweep Phase

**Traverse tree top-down to compute prefix sums**

**Starting from root (total=14):**
```
Level 0: root = 14
         Split: left=7, right=7

Level 1: [7, 14]
         Split left:  left=3, right=4  → [3, 7]
         Split right: left=4, right=3  → [11, 14]

Level 2: [3, 7, 11, 14]
         Split all pairs...

Final positions: [0, 1, 3, 4, 7, 10, 11, 13]
```

**This gives us the prefix sum - the bit position for each symbol!**

### Reduce-Merge GPU Kernel

```java
private static void reduceMergeKernel(
    int[] currentCodes,     // Input: codes from previous iteration
    byte[] currentLengths,  // Input: lengths from previous iteration
    int[] outputCodes,      // Output: merged codes
    byte[] outputLengths,   // Output: merged lengths
    int[] positions,        // Output: cumulative bit positions
    int numPairs            // Number of pairs to process
) {
    for (@Parallel int i = 0; i < numPairs; i++) {
        int leftIdx = 2 * i;
        int rightIdx = 2 * i + 1;
        
        // Read left and right pair
        int leftCode = currentCodes[leftIdx];
        byte leftLen = currentLengths[leftIdx];
        int rightCode = currentCodes[rightIdx];
        byte rightLen = currentLengths[rightIdx];
        
        // Merge: combine two codes into one
        // Result: [leftCode][rightCode] as single bitstream
        int mergedCode = (leftCode << rightLen) | rightCode;
        byte mergedLen = leftLen + rightLen;
        
        // Store merged result
        outputCodes[i] = mergedCode;
        outputLengths[i] = mergedLen;
        
        // Store cumulative positions
        positions[leftIdx] = 0;  // Left starts at position 0 (relative)
        positions[rightIdx] = leftLen;  // Right starts after left
    }
}
```

### Complete Iteration Sequence

**Input:** 8 symbols with lengths `[1, 2, 1, 3, 3, 1, 2, 1]`

**Iteration 1: Merge adjacent pairs (8 → 4)**
```
Pair 0: merge(1,2) → length=3, positions=[0,1]
Pair 1: merge(1,3) → length=4, positions=[0,1]
Pair 2: merge(3,1) → length=4, positions=[0,3]
Pair 3: merge(2,1) → length=3, positions=[0,2]

Result: 4 merged codes with lengths [3,4,4,3]
```

**Iteration 2: Merge pairs of pairs (4 → 2)**
```
Pair 0: merge(3,4) → length=7
Pair 1: merge(4,3) → length=7

Result: 2 merged codes with lengths [7,7]
```

**Iteration 3: Merge final pairs (2 → 1)**
```
Pair 0: merge(7,7) → length=14

Result: 1 final code (complete bitstream)
```

**Total iterations:** log₂(8) = 3

**For 16MB chunk (16M symbols):** log₂(16M) ≈ 24 iterations

### Memory Ping-Pong Optimization

**Problem:** Can't read and write same array in parallel

**Solution:** Alternate between two buffers
```java
int[] buffer1 = new int[16M];
int[] buffer2 = new int[8M];

// Iteration 1: read buffer1, write buffer2
reduceMerge(buffer1, buffer2, 8M);

// Iteration 2: read buffer2, write buffer1 (reused)
reduceMerge(buffer2, buffer1, 4M);

// Iteration 3: read buffer1, write buffer2 (reused)
reduceMerge(buffer1, buffer2, 2M);

// Continue alternating...
```

**Memory saved:** Reuse instead of allocating each iteration!

### Performance Analysis

**Per iteration:**
```
Operations: N/2 pairs
Memory read: N × (4 bytes + 1 byte) = 5N bytes
Memory write: (N/2) × (4 bytes + 1 byte) = 2.5N bytes
Total bandwidth: 7.5N bytes

Iteration 1: 7.5 × 16M = 120 MB → ~2.5 ms @ 48 GB/s
Iteration 2: 7.5 × 8M = 60 MB → ~1.25 ms
Iteration 3: 7.5 × 4M = 30 MB → ~0.6 ms
...
Iteration 24: 7.5 × 1 = 7.5 bytes → ~0 ms

Total: ~5 ms for all iterations (geometric series)
```

---

## 📦 Stage 3: GPU Bitstream Packing

### The Challenge

**We now have:**
- `codes[N]`: Huffman code for each symbol
- `lengths[N]`: Code length for each symbol
- `positions[N]`: Absolute bit position for each symbol (from Stage 2!)

**Need to produce:**
- `output[]`: Packed byte array with all codes concatenated

**Complexity:**
- Codes are variable length (1-16 bits)
- Codes can span byte boundaries
- Need atomic operations for overlapping writes






///// yuvraj reached


### Bit-Level Packing

**Example:** Write code `0b110` (3 bits) at bit position 5

```
Output byte array (initially zeros):
Byte 0: [0 0 0 0 0 0 0 0]
        bit 7 ← → bit 0

Byte 1: [0 0 0 0 0 0 0 0]
        bit 15 ← → bit 8

Byte 2: [0 0 0 0 0 0 0 0]
        bit 23 ← → bit 16

Write 0b110 at bit position 5:
         ↓bit 5
Byte 0: [0 0|1 1 0|0 0 0]
            └──┬──┘
           code spans bits 7-5

Result in memory:
Byte 0: bits written at positions [7:5] = 0b110
```

**Bit manipulation:**
```java
int bitPos = 5;
int code = 0b110;
int length = 3;

int byteIdx = bitPos / 8;  // = 0
int bitOffset = bitPos % 8;  // = 5

// Shift code to correct position within byte
int mask = code << bitOffset;  // 0b110 << 5 = 0b11000000

// Write to output (OR to preserve existing bits)
output[byteIdx] |= (byte) mask;
```

### Handling Multi-Byte Codes

**Example:** Write code `0b11010101` (8 bits) at bit position 4

```
Spans two bytes!

Byte 0: bit position 4-7 (4 bits)
Byte 1: bit position 8-11 (4 bits)

Split code:
Lower 4 bits: 0b0101 → write to byte 0, bits [7:4]
Upper 4 bits: 0b1101 → write to byte 1, bits [3:0]
```

**Algorithm:**
```java
int bitPos = 4;
int code = 0b11010101;
int length = 8;

int byteIdx = bitPos / 8;  // = 0
int bitOffset = bitPos % 8;  // = 4

int bitsInFirstByte = 8 - bitOffset;  // = 4
int bitsInSecondByte = length - bitsInFirstByte;  // = 4

if (bitsInSecondByte <= 0) {
    // Fits in single byte
    output[byteIdx] |= (byte) (code << bitOffset);
} else {
    // Spans two bytes
    int lowerBits = code & ((1 << bitsInFirstByte) - 1);  // 0b0101
    int upperBits = code >> bitsInFirstByte;  // 0b1101
    
    output[byteIdx] |= (byte) (lowerBits << bitOffset);
    output[byteIdx + 1] |= (byte) upperBits;
}
```

### GPU Kernel Implementation

```java
private static void packBitstreamKernel(
    int[] codes,        // Input: Huffman codes (16M)
    byte[] lengths,     // Input: Code lengths (16M)
    int[] positions,    // Input: Bit positions from Stage 2 (16M)
    byte[] output,      // Output: Packed bitstream (~2MB)
    int N               // Number of symbols
) {
    for (@Parallel int i = 0; i < N; i++) {
        int code = codes[i];
        int length = lengths[i];
        int bitPos = positions[i];
        
        if (length == 0) continue;  // Skip empty
        
        int byteIdx = bitPos / 8;
        int bitOffset = bitPos % 8;
        
        int bitsInFirstByte = Math.min(8 - bitOffset, length);
        int bitsInSecondByte = length - bitsInFirstByte;
        
        // Write first byte (or part)
        int mask1 = (code & ((1 << bitsInFirstByte) - 1)) << bitOffset;
        atomicOR(output, byteIdx, (byte) mask1);
        
        // Write second byte if needed
        if (bitsInSecondByte > 0) {
            int mask2 = code >> bitsInFirstByte;
            atomicOR(output, byteIdx + 1, (byte) mask2);
        }
        
        // Handle third byte for codes > 16 bits (rare)
        if (bitsInSecondByte > 8) {
            int mask3 = code >> (bitsInFirstByte + 8);
            atomicOR(output, byteIdx + 2, (byte) mask3);
        }
    }
}
```

### Atomic Operations

**Problem:** Multiple threads may write to same byte

```
Thread 0: Write code at bit position 5-7 (3 bits)
Thread 1: Write code at bit position 8-9 (2 bits)

Both touch byte 1!
```

**Solution:** Use atomic OR operation
```java
atomicOR(output, byteIdx, mask);

// Hardware ensures:
// 1. Read current value
// 2. OR with mask
// 3. Write back
// All as atomic transaction (no race conditions)
```

**TornadoVM Implementation:**
```java
// TornadoVM provides atomic primitives
uk.ac.manchester.tornado.api.types.arrays.ByteArray.atomicOr(index, value);
```

### Memory Access Pattern

**Output array:** ~2 MB (compressed size)
**Input positions:** 16M int array (64 MB)

**Access pattern:**
```
Thread 0: write to output[0:3]
Thread 1: write to output[1:4]    ← overlaps with thread 0!
Thread 2: write to output[3:6]
...

Non-coalesced writes due to variable lengths
→ Slower than Stage 1, but still parallel!
```

### Performance Analysis

```
Operations: 16M symbol writes
Atomic OR per symbol: 1-3 operations (avg ~1.5)
Total atomic ops: 24M

GPU atomic throughput: ~500M ops/s
Time: 24M ÷ 500M = ~48 ms

Memory traffic: 
  Read positions: 64 MB
  Read codes/lengths: 80 MB
  Write output: 2 MB (with atomic overhead ~10 MB)
  Total: ~154 MB

Bandwidth time: 154 MB ÷ 48 GB/s = ~3.2 ms

Total: ~48 ms (atomic ops dominate)
```

**Bottleneck:** Atomic operations to handle overlapping byte writes

---

## 🔬 Complete Example Walkthrough

Let's trace the entire pipeline for a small example:

**Input:** `"AAABBC"` (6 bytes)

**Huffman Codebook:**
```
A: frequency=3, code=0b0,  length=1
B: frequency=2, code=0b10, length=2  
C: frequency=1, code=0b11, length=2
```

---

### Stage 1: GPU Codebook Lookup

**Input:** `symbols[6] = ['A', 'A', 'A', 'B', 'B', 'C']`

**GPU Execution (6 threads in parallel):**
```
Thread 0: symbol='A' → code=0b0,  length=1
Thread 1: symbol='A' → code=0b0,  length=1
Thread 2: symbol='A' → code=0b0,  length=1
Thread 3: symbol='B' → code=0b10, length=2
Thread 4: symbol='B' → code=0b10, length=2
Thread 5: symbol='C' → code=0b11, length=2
```

**Output:**
```
codes[6]   = [0b0, 0b0, 0b0, 0b10, 0b10, 0b11]
lengths[6] = [1,   1,   1,   2,    2,    2]
```

---

### Stage 2: GPU Reduce-Merge Iterations

**Goal:** Compute prefix sum of lengths → bit positions

**Iteration 1: Merge pairs (6 → 3)**

GPU processes 3 pairs in parallel:

```
Thread 0: Merge pair (0,1)
  Left:  code=0b0, length=1
  Right: code=0b0, length=1
  Merged: code=0b00 (0b0 << 1 | 0b0), length=2
  Positions: left=0, right=1

Thread 1: Merge pair (2,3)
  Left:  code=0b0, length=1
  Right: code=0b10, length=2
  Merged: code=0b010 (0b0 << 2 | 0b10), length=3
  Positions: left=0, right=1

Thread 2: Merge pair (4,5)
  Left:  code=0b10, length=2
  Right: code=0b11, length=2
  Merged: code=0b1011 (0b10 << 2 | 0b11), length=4
  Positions: left=0, right=2
```

**Intermediate state:**
```
mergedCodes[3]   = [0b00, 0b010, 0b1011]
mergedLengths[3] = [2,    3,     4]
partialPos[6]    = [0, 1, 0, 1, 0, 2]  (relative to pair)
```

**Iteration 2: Merge pairs (3 → 1 + remainder)**

GPU processes 1 pair + 1 remainder:

```
Thread 0: Merge pair (0,1)
  Left:  code=0b00, length=2
  Right: code=0b010, length=3
  Merged: code=0b00010 (0b00 << 3 | 0b010), length=5
  Positions: left=0, right=2

Thread 1: No pair (remainder element 2)
  Just carry forward: code=0b1011, length=4
  Position: 0
```

**Intermediate state:**
```
mergedCodes[2]   = [0b00010, 0b1011]
mergedLengths[2] = [5,      4]
partialPos[3]    = [0, 2, 0]
```

**Now compute absolute positions by propagating through tree:**

```
Level 2: positions = [0, 5]  (second starts after first)
Level 1: positions = [0, 2, 5]  (split first pair)
Level 0: positions = [0, 1, 2, 3, 5, 7]  (split all pairs)
```

**Final bit positions:**
```
Symbol 0 (A): position = 0
Symbol 1 (A): position = 1
Symbol 2 (A): position = 2
Symbol 3 (B): position = 3
Symbol 4 (B): position = 5
Symbol 5 (C): position = 7
```

---

### Stage 3: GPU Bitstream Packing

**Input:**
```
codes[]    = [0b0,  0b0,  0b0,  0b10,  0b10,  0b11]
lengths[]  = [1,    1,    1,    2,     2,     2]
positions[] = [0,    1,    2,    3,     5,     7]
```

**GPU Execution (6 threads in parallel):**

```
Thread 0: Write 0b0 (1 bit) at position 0
  byteIdx = 0, bitOffset = 0
  mask = 0b0 << 0 = 0b00000000
  output[0] |= 0b00000000

Thread 1: Write 0b0 (1 bit) at position 1
  byteIdx = 0, bitOffset = 1
  mask = 0b0 << 1 = 0b00000000
  output[0] |= 0b00000000

Thread 2: Write 0b0 (1 bit) at position 2
  byteIdx = 0, bitOffset = 2
  mask = 0b0 << 2 = 0b00000000
  output[0] |= 0b00000000

Thread 3: Write 0b10 (2 bits) at position 3
  byteIdx = 0, bitOffset = 3
  mask = 0b10 << 3 = 0b00010000
  output[0] |= 0b00010000

Thread 4: Write 0b10 (2 bits) at position 5
  byteIdx = 0, bitOffset = 5
  mask = 0b10 << 5 = 0b01000000
  output[0] |= 0b01000000

Thread 5: Write 0b11 (2 bits) at position 7
  byteIdx = 0, bitOffset = 7
  Spans byte boundary!
  First byte: 1 bit at position 7
    mask1 = (0b11 & 0b1) << 7 = 0b10000000
    output[0] |= 0b10000000
  Second byte: 1 bit at position 8
    mask2 = 0b11 >> 1 = 0b1
    output[1] |= 0b00000001
```

**Final output (bit-by-bit):**
```
Byte 0: 0b11010000 = 0xD0
        │││││└└└─ bits 0-2: A=0, A=0, A=0 = 000
        │││└└──── bits 3-4: B=10
        ││└────── bits 5-6: B=10
        │└─────── bit 7: C[0]=1 (first bit of 0b11)

Byte 1: 0b00000001 = 0x01
        └─────────── bit 8 (=bit 0): C[1]=1 (second bit of 0b11)

Total: 9 bits = 0b000101011
```

**Verification:**
```
Original: "AAABBC"
Codes:    A=0, B=10, C=11
Expected: 0|0|0|10|10|11 = 000101011 ✓

Compressed size: 9 bits = 2 bytes
Original size: 6 bytes = 48 bits
Compression ratio: 2/6 = 33% (67% savings!)
```

---

## 🖥️ GPU Implementation Details

### TornadoVM Kernel Code

**Complete Phase 3 implementation:**

```java
public class Phase3GpuEncoder {
    
    // Stage 1: Lookup kernel
    private static void lookupKernel(
        byte[] input,
        int[] codeBits,      // Codebook
        byte[] codeLengths,  // Codebook
        int[] outputCodes,
        byte[] outputLengths,
        int N
    ) {
        for (@Parallel int i = 0; i < N; i++) {
            int symbol = input[i] & 0xFF;
            outputCodes[i] = codeBits[symbol];
            outputLengths[i] = codeLengths[symbol];
        }
    }
    
    // Stage 2: Reduce-merge kernel
    private static void reduceMergeKernel(
        int[] currentCodes,
        byte[] currentLengths,
        int[] outputCodes,
        byte[] outputLengths,
        int[] relativePositions,
        int numPairs
    ) {
        for (@Parallel int i = 0; i < numPairs; i++) {
            int leftIdx = 2 * i;
            int rightIdx = 2 * i + 1;
            
            int leftCode = currentCodes[leftIdx];
            byte leftLen = currentLengths[leftIdx];
            int rightCode = currentCodes[rightIdx];
            byte rightLen = currentLengths[rightIdx];
            
            // Merge codes
            int mergedCode = (leftCode << rightLen) | rightCode;
            byte mergedLen = (byte)(leftLen + rightLen);
            
            outputCodes[i] = mergedCode;
            outputLengths[i] = mergedLen;
            
            // Store relative positions within pair
            relativePositions[leftIdx] = 0;
            relativePositions[rightIdx] = leftLen;
        }
    }
    
    // Stage 3: Pack bitstream kernel
    private static void packBitstreamKernel(
        int[] codes,
        byte[] lengths,
        int[] positions,
        byte[] output,
        int N
    ) {
        for (@Parallel int i = 0; i < N; i++) {
            int code = codes[i];
            int length = lengths[i];
            int bitPos = positions[i];
            
            if (length == 0) continue;
            
            int byteIdx = bitPos / 8;
            int bitOffset = bitPos % 8;
            
            // Handle up to 16-bit codes
            if (length <= (8 - bitOffset)) {
                // Fits in single byte
                int mask = code << bitOffset;
                atomicOR(output, byteIdx, (byte) mask);
            } else if (length <= (16 - bitOffset)) {
                // Spans two bytes
                int bitsInFirst = 8 - bitOffset;
                int lowerMask = (code & ((1 << bitsInFirst) - 1)) << bitOffset;
                int upperMask = code >> bitsInFirst;
                
                atomicOR(output, byteIdx, (byte) lowerMask);
                atomicOR(output, byteIdx + 1, (byte) upperMask);
            } else {
                // Spans three bytes (rare, for long codes)
                int bitsInFirst = 8 - bitOffset;
                int bitsInSecond = Math.min(8, length - bitsInFirst);
                
                int mask1 = (code & ((1 << bitsInFirst) - 1)) << bitOffset;
                int mask2 = (code >> bitsInFirst) & 0xFF;
                int mask3 = code >> (bitsInFirst + 8);
                
                atomicOR(output, byteIdx, (byte) mask1);
                atomicOR(output, byteIdx + 1, (byte) mask2);
                atomicOR(output, byteIdx + 2, (byte) mask3);
            }
        }
    }
    
    // Atomic OR helper (TornadoVM wrapper)
    private static void atomicOR(byte[] array, int index, byte value) {
        uk.ac.manchester.tornado.api.types.arrays.ByteArray
            .atomicOr(array, index, value);
    }
}
```

### Task Graph Construction

```java
public byte[] executePhase3(byte[] input, HuffmanCode[] huffmanCodes) {
    int N = input.length;
    
    // Prepare codebook
    int[] codeBits = new int[256];
    byte[] codeLengths = new byte[256];
    for (int i = 0; i < 256; i++) {
        codeBits[i] = huffmanCodes[i].bits;
        codeLengths[i] = (byte) huffmanCodes[i].length;
    }
    
    // Stage 1: Lookup
    int[] codes = new int[N];
    byte[] lengths = new byte[N];
    
    TaskGraph stage1 = new TaskGraph("lookup")
        .transferToDevice(DataTransferMode.FIRST_EXECUTION,
            input, codeBits, codeLengths)
        .task("lookupTask", Phase3GpuEncoder::lookupKernel,
            input, codeBits, codeLengths, codes, lengths, N)
        .transferToHost(DataTransferMode.EVERY_EXECUTION,
            codes, lengths);
    
    ImmutableTaskGraph immutableStage1 = stage1.snapshot();
    TornadoExecutionPlan lookupPlan = new TornadoExecutionPlan(immutableStage1);
    lookupPlan.execute();
    
    // Stage 2: Reduce-merge iterations
    int[] currentCodes = codes;
    byte[] currentLengths = lengths;
    int currentSize = N;
    
    int iterations = (int) Math.ceil(Math.log(N) / Math.log(2));
    
    for (int iter = 0; iter < iterations; iter++) {
        int numPairs = currentSize / 2;
        int[] outputCodes = new int[numPairs + (currentSize % 2)];
        byte[] outputLengths = new byte[numPairs + (currentSize % 2)];
        int[] relativePos = new int[currentSize];
        
        TaskGraph mergeGraph = new TaskGraph("merge_" + iter)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                currentCodes, currentLengths)
            .task("mergeTask", Phase3GpuEncoder::reduceMergeKernel,
                currentCodes, currentLengths,
                outputCodes, outputLengths, relativePos, numPairs)
            .transferToHost(DataTransferMode.EVERY_EXECUTION,
                outputCodes, outputLengths, relativePos);
        
        ImmutableTaskGraph immutableMerge = mergeGraph.snapshot();
        TornadoExecutionPlan mergePlan = new TornadoExecutionPlan(immutableMerge);
        mergePlan.execute();
        
        // Handle remainder (odd element)
        if (currentSize % 2 == 1) {
            outputCodes[numPairs] = currentCodes[currentSize - 1];
            outputLengths[numPairs] = currentLengths[currentSize - 1];
        }
        
        // Compute absolute positions from relative
        // (CPU does this lightweight work)
        computeAbsolutePositions(relativePos, outputCodes, outputLengths);
        
        currentCodes = outputCodes;
        currentLengths = outputLengths;
        currentSize = numPairs + (currentSize % 2);
        
        mergePlan.freeDeviceMemory();
    }
    
    // Stage 3: Pack bitstream
    int[] finalPositions = computeFinalPositions(lengths);
    int outputSize = (finalPositions[N-1] + lengths[N-1] + 7) / 8;
    byte[] output = new byte[outputSize];
    
    TaskGraph packGraph = new TaskGraph("pack")
        .transferToDevice(DataTransferMode.FIRST_EXECUTION,
            codes, lengths, finalPositions)
        .task("packTask", Phase3GpuEncoder::packBitstreamKernel,
            codes, lengths, finalPositions, output, N)
        .transferToHost(DataTransferMode.EVERY_EXECUTION, output);
    
    ImmutableTaskGraph immutablePack = packGraph.snapshot();
    TornadoExecutionPlan packPlan = new TornadoExecutionPlan(immutablePack);
    packPlan.execute();
    
    // Cleanup
    lookupPlan.freeDeviceMemory();
    packPlan.freeDeviceMemory();
    
    return output;
}
```

### GPU Thread Configuration

**CUDA/OpenCL launch parameters:**

```
Grid configuration:
• Threads per block: 256 (warp size = 32, so 8 warps)
• Blocks: ceil(N / 256)
• Total threads: N (one per symbol)

Example for 16MB chunk (N = 16,777,216):
• Blocks: 65,536
• Threads per block: 256
• Total threads: 16,777,216
• Occupancy: 100% (all SM cores utilized)

Memory hierarchy:
• L1 cache: 128 KB per SM (shared among warps)
• L2 cache: 1024 KB (global, shared among all SMs)
• Global memory: 2 GB VRAM
```

---

## 🧮 Memory Layout & Optimization

### Memory Footprint Analysis

**Stage 1: Lookup**
```
Input:
  symbols[16M]:       16 MB
  codeBits[256]:      1 KB
  codeLengths[256]:   256 bytes

Output:
  codes[16M]:         64 MB (int = 4 bytes)
  lengths[16M]:       16 MB (byte = 1 byte)

Total: 96 MB
```

**Stage 2: Reduce-Merge (worst case - iteration 1)**
```
Input:
  currentCodes[16M]:   64 MB
  currentLengths[16M]: 16 MB

Output:
  outputCodes[8M]:     32 MB
  outputLengths[8M]:   8 MB
  relativePos[16M]:    64 MB (for position tracking)

Total: 184 MB (peak)
```

**Stage 3: Pack**
```
Input:
  codes[16M]:         64 MB
  lengths[16M]:       16 MB
  positions[16M]:     64 MB

Output:
  bitstream[~2MB]:    2 MB (compressed)

Total: 146 MB
```

**Peak Memory:** 184 MB (Stage 2, iteration 1)

**Phase 3 Total (all stages):** ~322 MB per chunk

### Optimization Strategies

#### 1. Array Reuse (Ping-Pong Buffers)

**Current (wasteful):**
```java
for (int iter = 0; iter < 24; iter++) {
    int[] outputCodes = new int[currentSize / 2];  // NEW allocation!
    reduceMerge(currentCodes, outputCodes);
    currentCodes = outputCodes;
}

Memory: 64 + 32 + 16 + 8 + ... = 127 MB wasted
```

**Optimized:**
```java
int[] buffer1 = new int[16M];  // Allocate once
int[] buffer2 = new int[16M];  // Allocate once

for (int iter = 0; iter < 24; iter++) {
    int[] input = (iter % 2 == 0) ? buffer1 : buffer2;
    int[] output = (iter % 2 == 0) ? buffer2 : buffer1;
    reduceMerge(input, output, currentSize / 2);
    currentSize /= 2;
}

Memory: 64 + 64 = 128 MB total (REUSED!)
Savings: 127 - 128 = -1 MB (wait, this is worse!)
```

**Actually, better approach:**
```java
int maxSize = 16M;
int[] buffer = new int[maxSize];  // Single buffer

for (int iter = 0; iter < 24; iter++) {
    int currentSize = maxSize >> iter;  // 16M, 8M, 4M, ...
    reduceMergeInPlace(buffer, currentSize);  // Overwrite in-place!
}

Memory: 64 MB total
Savings: 96 MB (60% reduction!)
```

#### 2. Compress Intermediate Results

**Observation:** Merged codes get longer but less frequent

**Iteration 1:** 16M codes, avg 4 bits each = 8 MB
**Iteration 5:** 512K codes, avg 64 bits each = 4 MB
**Iteration 10:** 16K codes, avg 1024 bits each = 2 MB

**Optimization:** Use bit-packed storage for intermediate codes
```java
// Instead of: int[16M] = 64 MB
// Use: BitSet or byte[] with variable-length encoding

BitSet packedCodes = new BitSet(totalBits);
int offset = 0;
for (int i = 0; i < currentSize; i++) {
    packedCodes.set(offset, offset + lengths[i], codes[i]);
    offset += lengths[i];
}

Memory: totalBits / 8 bytes ≈ 8 MB (8× reduction!)
```

#### 3. Eager Memory Freeing

**Current (memory leak during iterations):**
```java
for (int iter = 0; iter < 24; iter++) {
    TornadoExecutionPlan plan = createPlan();
    plan.execute();
    // Plan stays in memory! (5 MB overhead each)
}

Leaked: 24 × 5 MB = 120 MB
```

**Optimized:**
```java
for (int iter = 0; iter < 24; iter++) {
    TornadoExecutionPlan plan = createPlan();
    plan.execute();
    plan.freeDeviceMemory();  // FREE IMMEDIATELY!
    plan.clearProfiles();
    plan = null;
}
System.gc();  // Suggest cleanup

Leaked: 0 MB ✓
```

#### 4. Streaming Pipeline

**Current (batch processing):**
```
[Stage 1: All 16M] → [Stage 2: All 16M] → [Stage 3: All 16M]
Memory: 96 + 184 + 146 = 426 MB peak
```

**Optimized (streaming):**
```
[Stage 1: 1M] → [Stage 2: 1M] → [Stage 3: 1M] → Output
[Stage 1: 1M] → [Stage 2: 1M] → [Stage 3: 1M] → Output
...
(repeat 16 times)

Memory: (96 + 184 + 146) / 16 = 26 MB per stream
Peak: 26 MB × 2 streams = 52 MB (with double buffering)

Savings: 426 - 52 = 374 MB (88% reduction!)
```

### Theoretical Minimum Memory

**Absolute minimum (ignoring TornadoVM overhead):**

```
Input buffer:         16 MB (must keep original)
Codebook:             2 KB (256 codes)
Working buffer:       16 MB (for parallel operations)
Output buffer:        2 MB (compressed result)
────────────────────────────
TOTAL:                34 MB

Current Phase 3:      322 MB
Theoretical minimum:  34 MB
Optimization potential: 288 MB (90% reduction!)
```

**Realistic target with TornadoVM overhead:**
```
Base data:            34 MB
TornadoVM overhead:   20 MB (execution plans, profiling)
Safety margin:        10 MB
────────────────────────────
REALISTIC TARGET:     64 MB per chunk

Current: 322 MB
Target: 64 MB
Required reduction: 80%
```

**With 64 MB per chunk:**
```
4 parallel chunks: 4 × 64 = 256 MB
MX330 available: 1,648 MB
Utilization: 256 / 1648 = 15.5% (plenty of headroom!)

Could even do 10 parallel chunks: 10 × 64 = 640 MB (39% utilization)
```

---

## ⚠️ Why This Is Hard

### Challenges

1. **Inherent Sequentiality**
   - Huffman encoding is fundamentally sequential
   - Output position depends on ALL previous code lengths
   - Can't directly parallelize without algorithmic transformation

2. **Variable-Length Codes**
   - Codes range 1-16 bits (sometimes more)
   - Unpredictable output size
   - Complex bit manipulation across byte boundaries

3. **Memory Bandwidth**
   - Stage 2 has high memory traffic (read-write cycles)
   - Atomic operations in Stage 3 (synchronization overhead)
   - TornadoVM data transfer overhead (CPU ↔ GPU)

4. **GPU Limitations**
   - No dynamic memory allocation in kernels
   - Limited atomic operation performance
   - Warp divergence if code lengths vary widely

5. **Memory Pressure**
   - 322 MB per chunk is excessive for 2GB GPU
   - Multiple execution plans kept in memory
   - Intermediate arrays not reused efficiently

### Why Current Implementation Is Disabled

**Root Cause:** Phase 3 uses 3.2× more memory than Phase 2 (322 MB vs 100 MB)

**Symptoms with 4 parallel chunks:**
```
4 × 322 MB = 1,288 MB
MX330 available: ~1,500 MB
Margin: only 212 MB (14%)

Under load:
• TornadoVM compilation: +50 MB
• OS background tasks: +100 MB
• Kernel overhead: +50 MB
────────────────────────────
Actual free: 12 MB

Result: GPU OOM → Silent failures → Data corruption
```

**Fix Required:** Reduce Phase 3 memory to ~100 MB per chunk (Phase 2 level)

### Comparison with CPU Encoding

**CPU (Current):**
```
Algorithm: Sequential BitOutputStream
Time: 1,109 ms per 16MB chunk
Memory: 16 MB input + 2 MB output = 18 MB
Throughput: 14.4 MB/s

Advantages:
• Simple, reliable
• Low memory usage
• No GPU complexity

Disadvantages:
• Slow (single-threaded)
• 86% of total compression time
• Can't utilize GPU parallelism
```

**GPU Phase 3 (If optimized):**
```
Algorithm: Parallel reduction + packing
Time: ~100 ms per 16MB chunk (estimated)
Memory: ~100 MB per chunk (target)
Throughput: 160 MB/s (11× faster!)

Advantages:
• Massive parallelism (16M threads)
• 11× speedup potential
• Better GPU utilization

Disadvantages:
• Complex algorithm
• High memory usage (current)
• Atomic operation overhead
• Requires optimization to be viable
```

---

## 🎓 Summary

### Key Concepts

1. **Parallel Prefix Sum:** Core algorithm that enables parallel Huffman encoding
2. **Reduction Tree:** Hierarchical merging in O(log N) GPU passes
3. **Bit-Level Packing:** Variable-length codes packed into byte stream
4. **Atomic Operations:** Synchronization for overlapping byte writes

### Performance Characteristics

| Stage | Operation | Time (16MB) | Memory |
|-------|-----------|-------------|---------|
| 1. Lookup | Codebook lookup | ~2 ms | 96 MB |
| 2. Reduce-Merge | Prefix sum tree | ~5 ms | 184 MB |
| 3. Pack | Bitstream packing | ~48 ms | 146 MB |
| **Total** | | **~55 ms** | **322 MB** |

**vs CPU:** 1,109 ms (20× slower), 18 MB (18× less memory)

### Path Forward

**Phase 3.2 Optimization Roadmap:**

1. **Implement array reuse** (ping-pong buffers)
   - Target: 184 MB → 128 MB (30% reduction)

2. **Eager memory freeing** (immediate cleanup)
   - Target: Remove 24 MB TornadoVM overhead

3. **Streaming pipeline** (process in batches)
   - Target: 322 MB → 64 MB (80% reduction)

4. **Re-enable Phase 3** with validation
   - Test with 4 parallel chunks (256 MB total)
   - Monitor for GPU OOM
   - Measure actual speedup

**Expected Result:**
- Memory: 322 MB → 64 MB per chunk ✓
- 4 parallel: 256 MB (safe for MX330) ✓
- Encoding time: 1,109 ms → 100 ms ✓
- Overall throughput: 12.6 MB/s → 71 MB/s (5.6× improvement) ✓

---

**Document Version:** 1.0  
**Target Audience:** Advanced developers with GPU programming knowledge  
**Prerequisites:** Understanding of parallel algorithms, GPU architectures, and information theory  
**Next Steps:** Implement Phase 3.2 memory optimizations as outlined above
