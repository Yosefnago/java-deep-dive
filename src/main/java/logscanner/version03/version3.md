# Version 03: Word-Aligned Reads & Zero-Allocation Aggregation

Version 02 eliminated `String` objects but left two problems unfixed:</br>
1. `HashMap<Integer, Integer>` was still boxing primitives.
2.  The ERROR check was reading bytes in the wrong order.

Version 03 resolves both and in doing
so, removes the Garbage Collector from the logscanner equation entirely.

---

## What Changed From Version 02

| Problem in V02                              | Fix in V03                                           |
|:--------------------------------------------|:-----------------------------------------------------|
| `HashMap<Integer, Integer>` boxing          | `int[24]` primitive array - `times[hour]++`          |
| Hour bytes read before ERROR check          | ERROR checked first, hour bytes only read on a match |
| Two separate `buffer.get()` calls for ERROR | Single `buffer.getInt()` reads 4 bytes at once       |
| `for` loop                                  | `while` loop cleaner pointer-style semantics         |

---

## How It Works

**Reading 4 bytes in one instruction**
```java
if (buffer.getInt(targetPos) == 0x4552524F) { // targetPos - the starting index for log level
addTime(buffer.get(lineStart + 11), buffer.get(lineStart + 12)); // lineStart + 11 - starting index for hour
        }
```

In Version 02, detecting "ERROR" meant calling `buffer.get()` four times and
comparing each byte individually.</br> `getInt()` loads all four bytes into a single
32-bit CPU register in one operation,</br> then compares against the constant
`0x4552524F`.

That constant is the Big-Endian representation of the first four bytes of "ERROR":
```
0x45 = 'E'
0x52 = 'R'
0x52 = 'R'
0x4F = 'O'
```

`MappedByteBuffer` uses Big-Endian byte order by default, so the bytes are read
left-to-right as a single integer.</br> One comparison replaces four and importantly
hour bytes are only read *after* this check passes.

**Zero-allocation aggregation**
```java
public void addTime(int t1, int t2) { // example : 21:32 = t1 -> 2 , t2 -> 1 
    int hour = (t1 - 48) * 10 + (t2 - 48);
    times[hour]++; // update the hour idx 
}
```

`int[24]` replaces `HashMap<Integer, Integer>` completely.</br> There is no boxing,
no hashing, no object creation just an array index increment.</br> The hour is
derived from two bytes using the same ASCII arithmetic from V02: subtract 48
to get the digit value, multiply the tens digit by 10, add the units digit.

**Newline scanning with a `while` loop**
```java
while (lineStart < fileSize) {
    if (lineStart + 25 <= fileSize) {
        // inspect fixed offsets within this line
    }
    lineStart = findNextLine(buffer, lineStart, fileSize);
}
```

`findNextLine()` scans forward byte by byte until it hits `\n` (ASCII 10),
then returns the position after it.</br> The outer `while` advances `lineStart`
explicitly pointer-style, with no loop variable managing two things at once.

---

## Why These Changes Matter

**The `HashMap` was the last source of allocation.**

In V02, every call to `hours.merge(hour, 1, Integer::sum)` potentially created
a new `Integer` object on the Heap.</br> With thousands of error lines per file, this
was a constant trickle of small allocations enough to trigger GC occasionally.</br>
but not on every run, which is exactly why V02's variance was so high.

Replacing the map with `int[24]` means the aggregation structure is allocated
once at `@Setup` and never touched by the GC again during a run.

**Reading hour bytes after the ERROR check is the correct order.**

On a typical log file, the vast majority of lines are not errors. V02 was reading
hour bytes on every line and discarding them.</br> V03 reads them only on confirmed
error lines that means wasted work eliminated.

---

## Benchmark Results (JMH)

> Test file: ~1 million log lines, ~120 MB

| Benchmark          | Mode | Score         |  Error   | What It Tells Us                                                                                                                                                        |
|:-------------------|:-----|:--------------|:--------:|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Execution Time** | avgt | 194.866 ms/op | ± 24.546 | **4.5x faster than V01, 2x faster than V02.** The tight ±12% error is the most important number here - the JVM is now behaving deterministically because GC is gone.    |
| **GC Alloc Rate**  | avgt | 0.011 MB/sec  | ± 0.001  | Effectively zero. `int[]` + primitive arithmetic gives the GC nothing to track. The 0.011 MB/sec is JMH's own internal overhead, not application code.                  |
| **GC Count**       | avgt | ≈ 0 counts/op |    —     | No Stop-The-World events during the benchmark. The CPU runs uninterrupted for the entire measurement window.                                                            |
| **GC Time**        | avgt | 0 ms/op       |    —     | The JVM spent zero time on memory management. The bottleneck has fully shifted out of the JVM - we are now limited by OS page fault handling and memory bus throughput. |

The jump from ±402ms variance (V02) to ±24ms (V03) is the clearest signal in
these results.</br> It is not just that V03 is faster - it is that V03 is *consistent*.
When GC is gone, logscanner becomes predictable.

## Where the Ceiling Is Now

At zero GC allocation, the program has left the JVM's domain.</br> The remaining
194ms is spent on:

- **OS Page Faults** - The first time each memory-mapped page is accessed, the
  OS must load it from disk into the Page Cache.</br> This is kernel work, invisible
  to the JVM.
  </br>
- **Memory Bus Throughput** - Reading 120MB sequentially is ultimately limited
  by how fast data can move from RAM to CPU cache.
  </br>
- **`MappedByteBuffer` bounds checking** - Every `buffer.get()` and
  `buffer.getInt()` call performs an internal bounds check.</br> The JIT eliminates
  some of these, but not all.

---

## A Discovery: Bound Check Elimination (BCE)

While inspecting the C2-compiled assembly output for `addTime()`, something
unexpected appeared.
```java
int hour = (t1 - 48) * 10 + (t2 - 48);
times[hour]++;
```

Every array access in Java has an implicit safety check the JIT inserts:
```asm
mov r9d, [r10+0xc]   ; load array length from the array header in memory
cmp r8d, r9d         ; compare hour (r8d) against the length (r9d)
jae <UncommonTrap>   ; if hour >= length, jump and throw ArrayIndexOutOfBoundsException
inc ...              ; the actual increment - only reached if the check passed
```

The JIT *can* eliminate this but only if it can **mathematically prove** the</br>
index is always in bounds. With `int[24]` and a computed `hour` value derived</br>
from raw bytes, it cannot. The check stayed in the assembly.</br>

**Why manual guards don't help**
```java
if (hour >= 0 && hour <= 24) {
    times[hour]++;
}
```
Since `int[] times` is a mutable reference, the JIT cannot guarantee another</br>
thread hasn't replaced the array with a smaller one between the guard and the</br>
access. It keeps its own check to stay defensive - two checks instead of one,</br>
with no gain.

**The fix: bitmask + `final`**

Two conditions must be met together for full BCE:
```java
final int[] times = new int[32];

times[hour & 0x1F]++;
```

`0x1F` is `11111` in binary. The bitwise AND caps the result to 0–31 - the JIT's</br>
Range Analysis engine can prove this statically.</br> `final` tells the JIT
the array reference and its length (32) will never change - it is a contract,
not a hint.

With both conditions satisfied, the JIT performs a constant fold:</br> it compares
the maximum possible masked value (31) against the immutable array length (32).</br>
Since 31 < 32, the `mov`, `cmp`, and `jae` instructions are completely eliminated
from the assembly.</br> The increment becomes unconditional.

> ⚠️ This is an intentional trade-off: `hour & 0x1F` will silently write to
> `times[25–31]` if a malformed line produces an out-of-range hour, rather than
> throwing. Those slots are ignored when reading results. Safety is sacrificed
> for BCE.

## What Version 04 Explores

Version 04 takes the BCE discovery and goes further.</br> the goal is to reduce the
raw instruction count visible in the C2 assembly output, and to replace
`MappedByteBuffer` with the modern **Foreign Function & Memory (FFM) API**.

The goal of Version 04 is to measure whether these changes produce a real
reduction in execution time, or whether V03 has already reached the limit of
what single-threaded scalar processing can achieve on this workload.

---

**Next**: [Version 04 — BCE & MemorySegment →](./src/main/java/logscanner/version04/version4.md)
