package logscanner.version04;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public class Tester {

    static final Path path = Path.of("src/main/resources/example.txt");
    static final int[] times = new int[32];
    static final int TARGET_POS = 21;           // fixed offset where log level starts
    static final int TARGET_TIME = 11;          // fixed offset where hour starts
    static final int NEW_LINE = 10;             // '\n' in ASCII

    // '\n' replicated into all 8 byte lanes - used to XOR against loaded longs
    static final long NEW_LINE_MASK = 0x0A0A0A0A0A0A0A0AL;
    // bit 7 of each byte lane - isolates the borrow signal after subtraction
    static final long MSB_MASK = 0x8080808080808080L;
    // bit 0 of each byte lane - used to trigger borrow on zero bytes
    static final long LSB_MASK = 0x0101010101010101L;
    // 'E','R','R','O' packed as a Big-Endian int - first 4 bytes of "ERROR"
    static final int ERRO_MASK = 0x4F525245;

    static void main() {

        try(FileChannel channel = FileChannel.open(path,StandardOpenOption.READ);
            // arena releases the mapped memory deterministically when the try block closes
            Arena arena = Arena.ofConfined()){

            MemorySegment memorySegment = channel
                    .map(FileChannel.MapMode.READ_ONLY,0, channel.size(),arena);

            long currentPos = 0;
            long lineStart = 0;
            long size = memorySegment.byteSize();

            // SWAR loop - processes 8 bytes per iteration
            while (currentPos <= size - 8){
                // load 8 raw bytes into a long - single CPU instruction, no object creation
                long data = memorySegment.get(ValueLayout.JAVA_LONG_UNALIGNED, currentPos);

                // detect '\n' in any of the 8 byte lanes simultaneously
                // a set MSB in the result means that lane held a newline
                long markers = ((data ^ NEW_LINE_MASK) - LSB_MASK) & ~(data ^ NEW_LINE_MASK) & MSB_MASK;

                if (markers != 0){

                    // TZCNT instruction - converts bit position to byte position
                    long bytePos = Long.numberOfTrailingZeros(markers) >> 3;
                    long exactNewlineOffset = currentPos + bytePos;

                    processIfError(memorySegment, lineStart);

                    currentPos = exactNewlineOffset + 1;
                    lineStart = currentPos;
                    continue;
                }
                currentPos += 8;
            }
            // tail loop - handles the remaining < 8 bytes at end of file
            while (currentPos < size) {
                if (memorySegment.get(ValueLayout.JAVA_BYTE, currentPos) == NEW_LINE) {
                    processIfError(memorySegment, lineStart);
                    lineStart = currentPos + 1;
                }
                currentPos++;
            }
        }catch (IOException _){}
        System.out.println(Arrays.toString(times));
    }
    private static void processIfError(MemorySegment segment, long lineStart) {
        if (lineStart + 25 <= segment.byteSize()) {
            // reads 4 bytes in one load - compared against 'E','R','R','O' as a single int
            long levelData = segment.get(ValueLayout.JAVA_INT_UNALIGNED, lineStart + TARGET_POS);
            if (levelData == ERRO_MASK) {
                // reads 2 bytes in one load - low byte = tens digit, high byte = units digit
                short timeData = segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, lineStart + TARGET_TIME);
                int packed = timeData & 0xFFFF;
                int hour = ((packed & 0xFF) - 48) * 10 + ((packed >>> 8) - 48);
                // bitmask index - JIT can prove hour & 0x1F is always in [0,31], eliminating bounds check
                times[hour & 0x1F]++;
            }
        }
    }
}

