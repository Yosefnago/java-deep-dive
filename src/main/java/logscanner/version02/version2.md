# Version 02: Memory-Mapped I/O & Byte-Level Scanning

Version 01 proved that representing log lines as `String` objects is the bottleneck.</br>
Version 02 eliminates that entirely instead of asking Java to read the file,</br>
we let the operating system handle it, and we process the raw bytes directly.

---

## The Core Idea: Stop Copying Data

When you call `Files.readAllLines()`, here's what actually happens:
```
Disk → OS Page Cache → JVM Heap → String objects → your code
```

Data is read from disk into the OS's memory,</br> then *copied again* into the Java
Heap as `String` objects.</br> Every copy costs time and memory.

Memory Mapping removes the second copy:
```
Disk → OS Page Cache → your code (via pointer)
```

The file is never loaded onto the Java Heap.</br> Instead, the OS gives the JVM a
direct window into the same memory pages it uses internally.</br> Your code reads
bytes straight from there.

---

## How It Works

**Opening a memory-mapped view of the file**
```java
var channel = FileChannel.open(path, StandardOpenOption.READ);
MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
```

`FileChannel` gives us a low-level handle to the file.</br> `channel.map()` asks the
OS to memory-map it.</br> returning a `MappedByteBuffer` that behaves like a byte
array but lives in native memory, not on the Java Heap.

**Scanning byte by byte**
```java
for (int i = 0; i < fileSize; i++) {
    if (buffer.get(i) == '\n') {
        // process the line from lineStart to i
        lineStart = i + 1;
    }
}
```

Instead of splitting on newlines and producing substrings,</br> we walk the buffer
one byte at a time, tracking where each line starts.</br> When we find `\n`, we know
the line boundaries and can inspect specific byte offsets directly no string
construction required.

**Checking for ERROR without `String.contains()`**
```java
buffer.get(targetPos, slice);      // read 5 bytes at the ERROR position
if (slice[0] == 69 && slice[4] == 82) {  // 'E' == 69, 'R' == 82
    addTime(hSlice[0], hSlice[1]);
}
```

ASCII values are just numbers. `'E'` is `69`, `'R'` is `82`.</br> We check the first
and last bytes of the expected `"ERROR"` position.</br> If they match we have an
error line no `String`, no `contains()`, no allocation.

**Extracting the hour with arithmetic**
```java
public void addTime(int t1, int t2) {
    int hour = (t1 - 48) * 10 + (t2 - 48);
    hours.merge(hour, 1, Integer::sum);
}
```

ASCII digits start at `48` (`'0'` = 48, `'1'` = 49, etc.).</br> Subtracting 48 from
a digit byte gives its numeric value directly no `parseInt()`, no `String`
conversion.</br> Two subtractions and a multiply replace an entire string parsing call.

---

## What's Still Not Ideal

This version makes a significant step forward, but two issues remain:

**1. `HashMap<Integer, Integer>` still boxes primitives**
```java
hours.merge(hour, 1, Integer::sum);
```

`HashMap` cannot store raw `int` values it requires `Integer` objects.</br> Every
time we record an error hit, Java wraps the `int` in an `Integer` object on the
Heap.</br> For a file with hundreds of thousands of errors, that's hundreds of
thousands of small allocations.</br> This is why GC isn't fully eliminated here.

**2. Hour bytes are read before confirming the line is an error**
```java
buffer.get(targetPos, slice);   // check for ERROR
buffer.get(timePos, hSlice);    // read hour - happens before the check
if (slice[0] == 69 && slice[4] == 82) {
    addTime(hSlice[0], hSlice[1]);
}
```

`hSlice` is populated on every single line, then thrown away if it's not an
error.</br> The correct order is: check for ERROR first, only then read the hour bytes.

Both of these are fixed in Version 03.

---

## Benchmark Results (JMH)

> Test file: ~1 million log lines, ~120 MB

| Benchmark          | Mode | Score           |   Error   | What It Tells Us                                                                                                                                                                   |
|:-------------------|:-----|:----------------|:---------:|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Execution Time** | avgt | 394.970 ms/op   | ± 402.075 | **2.2x faster than V01** but the error is larger than the score itself, meaning results vary wildly run to run.</br> `HashMap` boxing is still triggering unpredictable GC pauses. |
| **GC Alloc Rate**  | avgt | 13.374 MB/sec   | ± 11.209  | **88% reduction vs V01.** The remaining 13MB/sec comes entirely from `Integer` boxing in `HashMap.merge()`.                                                                        |
| **GC Count**       | avgt | 2.000 counts/op |     —     | Down from 19 pauses to 2. The architecture shift worked - the JVM is no longer overwhelmed, but GC hasn't been eliminated.                                                         |
| **GC Time**        | avgt | 11.000 ms/op    |     —     | From 1801ms to 11ms. GC is no longer the bottleneck - but the high variance in execution time shows it still fires at unpredictable moments.                                       |

The speedup is real, but the instability in the numbers (±402ms on a 394ms mean)
tells us there's still something non-deterministic happening.</br> The culprit is the
`HashMap` - when GC fires to clean up boxed `Integer` objects, it can add
hundreds of milliseconds to an otherwise fast run.

---

## Root Cause of Remaining Variance

`HashMap<Integer, Integer>` creates a new `Integer` object for every unique hour
on insert and every time `merge()` produces an updated count.</br> With 24 possible
hour buckets and thousands of updates.</br> this is a steady stream of small heap
allocations enough to trigger GC.</br> but inconsistently depending on when the
Young Generation fills up during that particular run.

The fix is simple: replace `HashMap<Integer, Integer>` with `int[24]`.</br> An array
of primitives allocates nothing at runtime and lets us write `times[hour]++`
instead of boxing, hashing, and merging.

---

## What Version 03 Changes

| Problem in V02                              | Approach in V03                                                  |
|:--------------------------------------------|:-----------------------------------------------------------------|
| `HashMap<Integer, Integer>` boxing          | `int[24]` primitive array - zero allocation per hit              |
| Hour bytes read before ERROR check          | Read hour bytes only *after* confirming ERROR                    |
| Two separate `buffer.get()` calls for ERROR | Single `buffer.getInt()` reads 4 bytes in one CPU instruction    |
| `for` loop with byte-by-byte newline scan   | `while` loop - functionally identical, cleaner pointer semantics |

---

**Next**: [Version 03 - Zero Allocation →](../version03/version3.md)
