package logscanner.version02;

import org.openjdk.jmh.annotations.*;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LogScannerV02 {

    private Path path;
    private Map<Integer, Integer> hours;

    @Setup(Level.Trial)
    public void init() {
        path = Path.of("src/main/resources/input.txt");
    }

    @Setup(Level.Invocation)
    public void resetCounters() {
        hours = new HashMap<>(32);
    }

    @Benchmark
    public Map<Integer, Integer> measureLogicOnly() {
        try(var channel = FileChannel.open(path, StandardOpenOption.READ)) {

            long fileSize = channel.size();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

            int lineStart = 0;
            byte[] slice = new byte[5];
            byte[] hSlice = new byte[2];

            for (int i = 0; i < fileSize; i++) {
                if (buffer.get(i) == '\n') {

                    int targetPos = lineStart + 21;
                    int timePos = lineStart+11;

                    if (targetPos + 5 <= i) {
                        buffer.get(targetPos, slice);
                        buffer.get(timePos,hSlice);
                        if (slice[0] == 69 && slice[4] == 82){
                            addTime(hSlice[0], hSlice[1]);
                        }
                    }
                    lineStart = i + 1;
                }
            }
        }catch (IOException _){}
        return hours;
    }
    public void addTime(int t1,int t2){
        int hour = (t1 - 48) * 10 + (t2 - 48);
        hours.merge(hour, 1, Integer::sum);
    }
}
