# Version 04: SWAR Scanning & Foreign Memory API

Version 03 proved that eliminating the Garbage Collector makes logscanner
predictable.</br> But the scanner itself was still processing the file one byte at a
time - every byte read, every newline check, every branch executed individually.

Version 04 changes the fundamental unit of work.</br> Instead of one byte per
iteration, we process **8 bytes per iteration** using a technique called
**SWAR - SIMD Within A Register**.</br> We also replace the legacy `MappedByteBuffer`
with Java's modern **Foreign Function & Memory (FFM) API**.

---

## What Changed From Version 03

| Problem in V03                            | Fix in V04                                             |
|:------------------------------------------|:-------------------------------------------------------|
| Byte-by-byte newline scan                 | 8-byte SWAR scan - one CPU word per iteration          |
| `MappedByteBuffer` legacy API             | `MemorySegment` + `Arena` - modern FFM API             |
| Non-deterministic buffer deallocation     | `Arena.ofConfined()` - explicit, deterministic release |
| Scalar `buffer.get()` for hour extraction | `JAVA_SHORT_UNALIGNED` reads 2 bytes in one load       |
| Instance field `int[]`                    | Local variable cache - keeps hot data in registers     |

---

## How It Works

### SWAR: Processing 8 Bytes at Once

The core insight is that a 64-bit CPU register can hold 8 bytes simultaneously.</br>
If we can load 8 bytes at once and detect whether *any* of them is a newline
character without branching on each one individually - we cut the number of
iterations by 8.

**V03 - one byte per iteration:**
```java
while (start < fileSize && buffer.get(start) != 10) {
    start++;
}
```
Every iteration: one byte loaded, one comparison, one branch. For a 120MB file
that is ~120 million branches in the newline scan alone.

**V04 - eight bytes per iteration:**
```java
long data = memorySegment.get(ValueLayout.JAVA_LONG_UNALIGNED, currentPos);
```
`JAVA_LONG_UNALIGNED` reads 8 raw bytes into a `long` in a single CPU instruction.</br>
No object creation, no copying - the 8 bytes land directly in a CPU register.

---

### Detecting a Newline in All 8 Bytes Simultaneously

```java
long markers = ((data ^ NEW_LINE_MASK) - LSB_MASK) & ~(data ^ NEW_LINE_MASK) & MSB_MASK;
```

The three mask constants each carry one structural role:
```
NEW_LINE_MASK = 0x0A0A0A0A0A0A0A0A  - '\n' replicated into all 8 byte lanes
LSB_MASK      = 0x0101010101010101  - bit 0 of each byte lane
MSB_MASK      = 0x8080808080808080  - bit 7 of each byte lane
```

The algorithm works in three phases:

**Phase 1 - zero out any newline byte:**
`(data ^ NEW_LINE_MASK)` XORs every byte against `'\n'`.</br>
A byte that *was* `'\n'` becomes `0x00`. All other bytes become non-zero.

**Phase 2 - trigger a borrow on every zero byte:**
Subtracting `LSB_MASK` attempts to subtract 1 from every byte lane simultaneously.</br>
A byte that was `0x00` cannot subtract 1 - it borrows from the next byte, setting bit 7.</br>
Bytes that were non-zero subtract cleanly with no borrow.

**Phase 3 - isolate the borrow signal:**
The AND with `~(data ^ NEW_LINE_MASK)` masks out false positives.</br>
The final AND with `MSB_MASK` keeps only bit 7 of each lane.</br>
Result: **a set bit in the MSB position of each byte that was a newline.**

One expression. No loop. No branch per byte.</br>
Before SWAR, the CPU executed a `cmp` and `jne` for every single byte.</br>
After SWAR, it executes one `jnz` for every 8 bytes - **8x fewer branches, 8x fewer potential mispredictions.**

---

### Finding the Exact Newline Position

```java
long bytePos = Long.numberOfTrailingZeros(markers) >> 3;
long exactNewlineOffset = currentPos + bytePos;
```

`Long.numberOfTrailingZeros()` is a JVM intrinsic - the JIT compiles it to a
single `TZCNT` instruction on x86-64.</br>
Shifting right by 3 converts from bit position to byte position.</br>
No inner loop, no guessing - the exact newline offset is derived arithmetically.

---

### Tail Handling

When fewer than 8 bytes remain at the end of the file, SWAR cannot be used
safely.</br> A plain byte-by-byte loop handles the remainder:
```java
while (currentPos < size) {
    if (memorySegment.get(ValueLayout.JAVA_BYTE, currentPos) == NEW_LINE) {
        processIfError(memorySegment, lineStart);
        lineStart = currentPos + 1;
    }
    currentPos++;
}
```
This path executes at most 7 times per file - its cost is noise.

---

### Foreign Function & Memory API

`MappedByteBuffer` is a legacy API. Its `get()` methods carry internal dispatch
overhead that the JIT cannot always inline, and the mapped region is released
non-deterministically when the GC decides to collect the buffer object.</br>

The FFM API replaces this with two modern constructs:
```java
try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
     Arena arena = Arena.ofConfined()) {

    MemorySegment memorySegment = channel.map(
        FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
```

`Arena.ofConfined()` gives a confined arena.</br>
The mapped memory is released deterministically when the `try` block closes - not when the GC runs.</br>
This eliminates a source of non-determinism that `MappedByteBuffer` carried silently.

`MemorySegment` reads use `ValueLayout` constants that give the JIT explicit
type and alignment information, enabling better code generation:
```java
// ERROR check - 4 bytes in one load instead of four buffer.get() calls
long levelData = segment.get(ValueLayout.JAVA_INT_UNALIGNED, lineStart + TARGET_POS);
if (levelData == ERRO_MASK) { ... }  // 0x4F525245 = 'E','R','R','O' packed

// Hour - 2 bytes in one load instead of two buffer.get() calls
short timeData = segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, lineStart + TARGET_TIME);
int packed = timeData & 0xFFFF;
int hour = ((packed & 0xFF) - 48) * 10 + ((packed >>> 8) - 48);
```

Two bytes of the timestamp are loaded in a single instruction.</br>
The high and low bytes are extracted with bitwise operations - `buffer.get()` is never called twice.

---

### BCE and Local Variable Caching

The BCE fix from V03 carries over here, with the array stored as a local variable:
```java
final int[] localTimes = this.times;
```

Copying the field reference into a local before the hot loop gives the JIT a
stronger guarantee that the reference won't change mid-loop.</br>
This helps it avoid redundant null checks and enables tighter register allocation
across the entire benchmark method.

---

## Benchmark Results (JMH)

> Test file: ~1 million log lines, ~120 MB

| Benchmark          | Mode | Score         |  Error   | What It Tells Us                                                                                                                                   |
|:-------------------|:-----|:--------------|:--------:|:---------------------------------------------------------------------------------------------------------------------------------------------------|
| **Execution Time** | avgt | 78.569 ms/op  | ± 9.259  | **2.5x faster than V03, 11x faster than V01.** SWAR processes 8 bytes per iteration instead of 1 - the branch count in the hot loop dropped by 8x. |
| **GC Alloc Rate**  | avgt | 0.019 MB/sec  | ± 0.002  | Near zero. The slight uptick vs V03's 0.011 MB/sec is JMH infrastructure overhead, not application code.                                           |
| **GC Alloc Norm**  | avgt | 1540.589 B/op | ± 66.587 | ~1.5KB per operation - fixed overhead from JMH itself. Application allocation is zero.                                                             |
| **GC Count**       | avgt | ≈ 0 counts/op |    -     | Zero GC pauses. BCE and zero-allocation aggregation carry over from V03 unchanged.                                                                 |

---

## The Full Picture

| Version | Execution Time | GC Alloc Rate | GC Pauses | What Was Fixed                              |
|:--------|:---------------|:--------------|:---------:|:--------------------------------------------|
| V01     | 872ms ± 346ms  | 156 MB/sec    |   19/op   | Baseline                                    |
| V02     | 394ms ± 402ms  | 13.4 MB/sec   |   2/op    | `String` → bytes, `Files` → `mmap`          |
| V03     | 194ms ± 24ms   | 0.011 MB/sec  |   0/op    | `HashMap` → `int[]`, `get()×4` → `getInt()` |
| V04     | 78ms ± 9ms     | 0.019 MB/sec  |   0/op    | SWAR 8-byte scan, `MemorySegment`, BCE      |

**872ms → 78ms. Same file. Same correct output.**

The progression tells a clear story:</br>
- **V01 → V02**: The bottleneck was object allocation. `String` objects killed throughput.</br>
- **V02 → V03**: The bottleneck was residual boxing. `HashMap` kept GC alive.</br>
- **V03 → V04**: The bottleneck was branch count. SWAR eliminated 7 out of every 8 newline checks.</br>

Each version fixed exactly one thing - and the numbers reflect that precisely.

---

**Status**: V04 is the final version in this series.

It was a great journey to look under the hood of the JVM - from naive string
allocation all the way down to SWAR bit manipulation and zero-branch hot loops.

If I missed something or got something wrong, feel free to reach out.</br>
And don't forget to ⭐ the repo if you found it useful.