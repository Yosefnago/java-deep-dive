package logscanner.version03;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.nio.MappedByteBuffer;
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
public class LogScannerV03 {

    Path path;
    int [] times;

    @Setup(Level.Trial)
    public void init() {
        path = Path.of("src/main/resources/input.txt");
    }

    @Setup(Level.Invocation)
    public void resetCounters() {
        this.times = new int[24];
    }

    @Benchmark
    public int[] measureLogicOnly() {
        try(var channel = FileChannel.open(path, StandardOpenOption.READ)) {

            long fileSize = channel.size();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

            int lineStart = 0;

            while (lineStart < fileSize) {

                if (lineStart + 25 <= fileSize) {
                    int targetPos = lineStart + 21;

                    if (buffer.getInt(targetPos) == 0x4552524F) {
                        addTime(buffer.get(lineStart + 11), buffer.get(lineStart + 12));
                    }
                }
                lineStart = findNextLine(buffer, lineStart, fileSize);
            }

        }catch (IOException e){
            throw new RuntimeException(e);
        }
        return times;
    }
    public int findNextLine(MappedByteBuffer buffer, int start, long fileSize) {
        while (start < fileSize && buffer.get(start) != 10) {
            start++;
        }
        return start + 1;
    }
    public void addTime(int t1,int t2){
        int hour = (t1 - 48) * 10 + (t2 - 48);
        times[hour]++;
    }
}

