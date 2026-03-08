package logscanner.version04;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LogScannerV04 {

     Path path;
     int[] times;
     final int TARGET_POS = 21;
     final int TARGET_TIME = 11;
     final int NEW_LINE = 10;
     final long NEW_LINE_MASK = 0x0A0A0A0A0A0A0A0AL;
     final long MSB_MASK = 0x8080808080808080L;
     final long LSB_MASK = 0x0101010101010101L;
     final int ERRO_MASK = 0x4F525245;

    @Setup(Level.Trial)
    public void init() {
        path = Path.of("src/main/resources/input.txt");
    }

    @Setup(Level.Invocation)
    public void resetCounters() {
        this.times = new int[32];
    }

    @Benchmark
    public int[] measureLogicOnly() {
        final int[] localTimes = this.times;
        final int localErroMask = this.ERRO_MASK;
        final long nlMask = this.NEW_LINE_MASK;
        final long lsbM = this.LSB_MASK;
        final long msbM = this.MSB_MASK;

        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            Arena arena = Arena.ofConfined()){

            MemorySegment memorySegment = channel
                    .map(FileChannel.MapMode.READ_ONLY,0, channel.size(),arena);

            long currentPos = 0;
            long lineStart = 0;
            long size = memorySegment.byteSize();

            while (currentPos <= size - 8){
                long data = memorySegment.get(ValueLayout.JAVA_LONG_UNALIGNED, currentPos);
                long markers = ((data ^ nlMask) - lsbM) & ~(data ^ nlMask) & msbM;

                if (markers != 0){
                    long bytePos = Long.numberOfTrailingZeros(markers) >> 3;
                    long exactNewlineOffset = currentPos + bytePos;

                    processIfError(memorySegment, lineStart, localTimes, localErroMask);

                    currentPos = exactNewlineOffset + 1;
                    lineStart = currentPos;
                    continue;
                }
                currentPos += 8;
            }
            while (currentPos < size) {
                if (memorySegment.get(ValueLayout.JAVA_BYTE, currentPos) == NEW_LINE) {
                    processIfError(memorySegment, lineStart, localTimes, localErroMask);
                    lineStart = currentPos + 1;
                }
                currentPos++;
            }

        }catch (IOException _){}
        return localTimes;
    }
    private void processIfError(MemorySegment segment, long lineStart, int[] localTimes, int erroMask) {
        if (lineStart + 25 <= segment.byteSize()) {
            long levelData = segment.get(ValueLayout.JAVA_INT_UNALIGNED, lineStart + TARGET_POS);
            if (levelData == erroMask) {
                short timeData = segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, lineStart + TARGET_TIME);
                int packed = timeData & 0xFFFF;
                int hour = ((packed & 0xFF) - 48) * 10 + ((packed >>> 8) - 48);
                localTimes[hour & 0x1F]++;
            }
        }
    }
}
