# Java Performance Engineering - A Log Scanner Case Study

This repository documents a logscanner engineering journey in Java.</br>  Starting from
the most natural, readable solution and improving it step by step.</br> each version
isolates one bottleneck, fixes it, measures the result, and explains why it worked.

The goal is not just to arrive at a fast solution.</br>  It is to understand *why* the
naive solution is slow, and *what exactly* each change does to the hardware and
the JVM.

## Test Environment

All benchmarks were run on a single thread - no concurrency, no parallelism.</br>
Every number reflects the performance of a single-threaded scan on one core.

|         |                                                 |
|:--------|:------------------------------------------------|
| **CPU** | Intel Core i5-1035G1 @ 1.00GHz (1.19 GHz boost) |
| **RAM** | 8.00 GB                                         |
| **OS**  | Windows x64 (64-bit)                            |
| **JDK** | Java 25                                         |

---

## The Task

We have a log file containing million lines.</br>  Each line is a single log event
produced by an application errors, warnings, debug messages, and others:
```
2026-03-05 01:28:28 [ERROR] User login failed - invalid credentials
2026-03-05 01:28:31 [DEBUG] Session cache hit for token abc123
2026-03-05 01:28:45 [WARN]  Response time exceeded threshold: 1842ms
2026-03-05 02:14:03 [ERROR] Database connection timeout after 30s
```

The log level (`ERROR`, `WARN`, `DEBUG`, etc.) and the timestamp are always at
fixed offsets from the start of the line.</br> The message after the log level is in random length
so the total line length is not guaranteed to be the same.

**The problem**: scan the entire file, find every `ERROR` line, and produce a
count of how many errors occurred in each hour of the day.

Expected output - errors grouped by hour (0–23) in array:
```
Hour 00 →  143 errors
Hour 01 →  311 errors
Hour 02 →   87 errors
...
Hour 23 →  204 errors

array [143, 311, 87...etc ]
each index represent the hour.
```

Simple problem. The interesting part is how you solve it.

---

## Why This Problem?

Log analysis is one of the most common real-world workloads in backend systems.</br>
It is also a perfect case study for logscanner engineering because:

- The input is large enough that naive solutions visibly struggle
- The algorithm itself is trivial the bottleneck is never the logic, always the I/O and memory model
- Every optimization targets something concrete and measurable: heap allocation, GC pressure, CPU instruction count

This makes it easy to isolate what each change actually does rather than
guessing why something got faster.

---

## Repository Structure

Each version lives in its own package and has its own documentation:

```
src/
└── main/
    ├── java/
    │   ├── generator/
    │   │   └── LogGenerator.java          ← generates the input.txt benchmark file
    │   └── logscanner/
    │       └── version0X/
    │           ├── LogScannerV0X.java     ← JMH benchmark class
    │           ├── Tester.java            ← algorithm test before going to JMH
    │           └── overview.md            ← documentation for this version
    └── resources/
        ├── example.txt                    ← small sample log file used by Tester
        ├── input.txt                      ← real benchmark input (~1M rows, ~120MB)
        └── runOptions.txt                 ← JVM flags and run commands
```

`Tester` exists because JMH is not a good environment for debugging it runs in
a forked JVM with no easy way to inspect output.</br> Every new algorithm is first
validated in `Tester` against `example.txt` with `System.out.println()` to confirm
the hour counts are correct.</br> then ported into the `LogScanner` benchmark class to
run against `input.txt` for real measurement.

`runOptions.txt` contains the exact commands used to run each benchmark and the
JVM flags used when inspecting assembly output:
```bash
# Running benchmarks with GC profiling
java -jar target/benchmarks.jar logscanner.version01.LogScannerV01 -prof gc
java -jar target/benchmarks.jar logscanner.version02.LogScannerV02 -prof gc
java -jar target/benchmarks.jar logscanner.version03.LogScannerV03 -prof gc
java -jar target/benchmarks.jar logscanner.version04.LogScannerV04 -prof gc

# Printing C2-compiled assembly for a specific method (used in V04 BCE investigation)
-XX:+UnlockDiagnosticVMOptions
-Xbatch -XX:CompileCommand=quiet
-XX:CompileCommand=compileonly,logscanner/version04/Tester.<methodName>
-XX:CompileCommand=print,logscanner/version04/Tester.<methodName>
```

| Version              | Approach                            | Key Idea                                              |
|:---------------------|:------------------------------------|:------------------------------------------------------|
| [V01](./version1.md) | `Files.readAllLines()` + Stream API | Baseline, readable, straightforward, GC-bound         |
| [V02](./version2.md) | `MappedByteBuffer` + byte scanning  | Eliminate `String` objects, move I/O off the Heap     |
| [V03](./version3.md) | `getInt()` + `int[24]`              | Remove the last allocations, reach zero GC            |
| [V04](./version4.md) | BCE bitmask + `MemorySegment`       | Eliminate JIT bounds checks, modernize the memory API |

---

## How to Read This Repository

Each version's document follows the same structure:

1. **What changed** - a clear table of what this version does differently from the last
2. **How it works** - a walkthrough of the actual code with explanation
3. **Why it matters** - the reasoning behind the change, not just the result
4. **Benchmark results** - real JMH numbers with analysis of what they mean
5. **What comes next** - what the numbers reveal about the remaining bottleneck

The benchmarks are run with [JMH](https://github.com/openjdk/jmh) in `AverageTime`
mode over a ~120MB file with one million log lines.</br> GC allocation
rate, GC count, and GC time are tracked alongside execution time so that every
source of overhead is visible.

---

## The Bottom Line

| Version | Execution Time | GC Alloc Rate | GC Pauses |
|:--------|:---------------|:--------------|:---------:|
| V01     | 872ms ± 346ms  | 156   MB/sec  |   19/op   |
| V02     | 394ms ± 402ms  | 13.4  MB/sec  |   2/op    |
| V03     | 194ms ± 24ms   | 0.011 MB/sec  |   0/op    |
| V04     | 78ms  ± 9ms    | 0.019 MB/sec  |   0/op    |

From ~870ms and 19 GC pauses per run, to ~78ms and zero GC on the same file,</br>
with the same correct output,</br> by changing only how the data is represented and
accessed.

---

## Requirements

- Java 22+ (for FFM API in V04)
- Maven
- JMH (included via `pom.xml`)

---

*Each version is a step, not a rewrite. Read them in order.*