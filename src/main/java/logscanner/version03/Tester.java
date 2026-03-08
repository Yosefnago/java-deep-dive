package logscanner.version03;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;


public class Tester {
    static Path path = Path.of("src/main/resources/example.txt");
    // primitive array replaces HashMap - no boxing, no hashing, index is the hour
    static final int [] times = new int[24];
    static MappedByteBuffer buffer;
    static long fileSize;

    static void main() {

        try(var channel = FileChannel.open(path, StandardOpenOption.READ)) {

            long fileSize = channel.size();
            buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            int lineStart = 0;

            while (lineStart < fileSize) {

                if (lineStart + 25 <= fileSize) {
                    int targetPos = lineStart + 21; // fixed offset where log level starts
                    int timePos = lineStart + 11;   // fixed offset where hour starts

                    // reads 4 bytes in one instruction instead of four buffer.get() calls
                    // 0x4552524F = 'E','R','R','O' in Big-Endian - first 4 bytes of "ERROR"
                    int word = buffer.getInt(targetPos);

                    if (word == 0x4552524F) {
                        addTime(buffer.get(timePos), buffer.get(timePos + 1));
                    }
                }
                lineStart = findNextLine(lineStart);
            }
        }catch (IOException _){}
        System.out.println(Arrays.toString(times));
    }
    // scans forward byte by byte until '\n' (ASCII 10) - one branch per byte
    public static int findNextLine(int start) {
        while (start < fileSize && buffer.get(start) != 10) {
            start++;
        }
        return start + 1;
    }
    public static void addTime(int t1,int t2){
        // ASCII digits: subtract 48 to get numeric value ('2' - 48 = 2)
        int hour = (t1 - 48) * 10 + (t2 - 48);
        times[hour]++;
    }

}

