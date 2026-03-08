package logscanner.version02;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public class Tester {

    static Path path = Path.of("src/main/resources/example.txt");

    // Integer keys and values - every merge() call boxes the hour int into an Integer object
    static Map<Integer,Integer> hours  = new HashMap<>(23);

    static void main() {

        try(var channel = FileChannel.open(path, StandardOpenOption.READ)) {

            long fileSize = channel.size();
            // maps the file directly into memory - no heap copying, no InputStream
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

            int lineStart = 0;
            byte[] slice = new byte[5];   // reused buffer for the log level bytes
            byte[] Hslice = new byte[2]; // reused buffer for the hour bytes

            // scan byte by byte - every iteration is one byte, one branch
            for (int i = 0; i < fileSize; i++) {
                if (buffer.get(i) == '\n') {

                    int targetPos = lineStart + 21; // fixed offset where log level starts
                    int timePos = lineStart+11;    // fixed offset where hour starts

                    if (targetPos + 5 <= i) {
                        buffer.get(targetPos, slice); // copies 5 bytes into slice
                        buffer.get(timePos,Hslice);  // copies 2 bytes into Hslice

                        // 'E' = 69, 'R' = 82 - checks first and last byte of "ERROR"
                        if (slice[0] == 69 && slice[4] == 82){
                            var t1 = Hslice[0];
                            var t2 = Hslice[1];
                            addTime(t1, t2);
                        }
                    }
                    lineStart = i + 1;
                }
            }
        }catch (IOException _){
        }
    }
    public static void addTime(int t1,int t2){
        // ASCII digits: subtract 48 to get the numeric value ('2' - 48 = 2)
        int hour = (t1 - 48) * 10 + (t2 - 48);
        // merge boxes hour into Integer and allocates a lambda on every call
        hours.merge(hour, 1, Integer::sum);
    }
}
