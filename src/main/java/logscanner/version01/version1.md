# Version 01: The Naive Implementation

If you've ever written code that "just works" and later wondered why it falls
apart on large inputs this is that code, intentionally. Version 01 is the
starting point: readable, simple, and completely unprepared for scale.

---

## How It Works

The implementation has three steps: read the file, find the errors, count them by hour.

**Reading the file**

```java
List file = Files.readAllLines(path);
```

`Files.readAllLines()` is the most natural way to read a file in Java. It gives
you a clean `List<String>` where every element is one line. What it doesn't tell
you is that it loads the *entire file* into memory before you can do anything with it.
For a 120MB log file with a million lines, that means a million `String` objects
sitting on the Java Heap before a single line has been processed.

**Finding the errors**
```java
List errors = file.stream()
    .filter(s -> s.contains("ERROR"))
    .map(s -> s.substring(10, 28))
    .toList();
```

The stream pipeline first filters for error lines, then extracts the timestamp
substring from each match. This is the correct order `filter` before `map` 
so we only allocate substrings for lines we actually care about.

**Counting by hour**
```java
for (String err : errors) {
    String hourPart = err.substring(1, 3).trim();
    incrementHour(Integer.parseInt(hourPart));
}
```

For each error line, we extract two characters, trim whitespace, and parse them
as an integer to get the hour. `Integer.parseInt()` is convenient but it means
we're converting ASCII characters that are *already digits* into a number through
string parsing a step that byte-level code can skip entirely.

---

## What's Wrong With This Design

The code is easy to read and easy to understand. The problem is what's happening
underneath.

| Design Choice                | Hidden Cost                                                                                                                            |
|:-----------------------------|:---------------------------------------------------------------------------------------------------------------------------------------|
| `Files.readAllLines()`       | Every line becomes a `String` object on the Heap all at once, before processing starts                                                 |
| `.toList()` after filter/map | A second list of `String` objects is allocated to hold the filtered results                                                            |
| `substring()` per error line | Each match produces another new `String` object                                                                                        |
| `Integer.parseInt()`         | Converts digits that are already in memory into a number via string parsing                                                            |
| `h0`–`h23` scalar fields     | 24 individual `int` fields with `switch`, `incrementHour()`, and `fillArr()` all boilerplate that `times[hour]++` replaces in one line |

Each of these is a small, reasonable choice in isolation. Together, on a million
lines, they create a cascade of short-lived objects that the Garbage Collector
has to continuously clean up.

---

## Benchmark Results (JMH)

> Test file: ~1 million log lines, ~120 MB

| Benchmark          | Mode | Score            |   Error   | What It Tells Us                                                                                                                          |
|:-------------------|:-----|:-----------------|:---------:|:------------------------------------------------------------------------------------------------------------------------------------------|
| **Execution Time** | avgt | 872.565 ms/op    | ± 346.478 | The ±40% variance is the real story, run time isn't consistent because GC pauses land at random points during execution.                  |
| **GC Alloc Rate**  | avgt | 156.071 MB/sec   | ± 57.054  | The allocator is producing 156MB of objects every second. Most of these are `String`s that live for microseconds before becoming garbage. |
| **GC Count**       | avgt | 19.000 counts/op |  ± 8.000  | The JVM stops the program 19 times per run to collect garbage. Each pause is time the CPU is not processing log lines.                    |
| **GC Time**        | avgt | 1801.000 ms/op   |     —     | The JVM spent more time collecting garbage than the program spent running. The bottleneck is not the algorithm it's memory management.    |

The key insight from these numbers: **this is not a slow algorithm, it's a
memory problem**. The logic of finding errors and counting hours is trivial.
What's expensive is the cost of representing every line as a Java object.

---

## Root Cause: The Memory Wall

When a program allocates objects faster than the GC can collect them, throughput
collapses. This is called hitting the **Memory Wall** and it shows up clearly here:
GC time (1801ms) is more than double the actual execution time (872ms).

The program isn't slow because the CPU can't process log lines fast enough.
It's slow because the CPU keeps getting interrupted to help clean up memory.

---

## What Version 02 Changes

The fix isn't a better algorithm it's a different mental model for how to
represent the data.

| Problem in V01                           | Approach in V02                                                       |
|:-----------------------------------------|:----------------------------------------------------------------------|
| Full file loaded as `String` objects     | Memory-map the file let the OS manage it, keep the Java Heap clean    |
| `String` allocation per line             | Work directly on raw bytes never construct a `String`                 |
| Stream API creating intermediate lists   | Manual `for` loop scanning bytes with index pointers                  |
| `Integer.parseInt()` for hour extraction | ASCII arithmetic: `(byte - 48)` gets the digit value in one operation |

> ⚠️ Version 02 will dramatically reduce allocation — but not eliminate it
> entirely. That's the goal of Version 03.

---

**Next**: [Version 02 — Memory-Mapped I/O →](../version02/version2.md)
