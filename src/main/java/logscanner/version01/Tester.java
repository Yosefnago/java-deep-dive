package logscanner.version01;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class Tester {

    private final int[] times= new int[24];
    Path path = Path.of("src/main/resources/example.txt");;
    private int h0, h1, h2, h3, h4, h5, h6, h7, h8, h9, h10, h11, h12, h13, h14, h15, h16, h17, h18, h19, h20, h21, h22, h23;

    public void main() throws IOException {

        // reads the entire file into memory - one String object per line, per filter, per map
        List<String> errors =
                Files.readAllLines(path).stream()
                .filter(s -> s.contains("ERROR"))
                .map(s -> s.substring(10, 28)) // allocates a new String per match
                .toList();

        for (String err : errors) {
            String hourPart = err.substring(1, 3).trim(); // another allocation per error line
            incrementHour(Integer.parseInt(hourPart));
        }
        fillArr();
        System.out.println(Arrays.toString(times));
    }

    // switch instead of array index - the JIT compiles this to a jump table
    private void incrementHour(int t) {
        switch (t) {
            case 0 -> h0++; case 1 -> h1++; case 2 -> h2++; case 3 -> h3++;
            case 4 -> h4++; case 5 -> h5++; case 6 -> h6++; case 7 -> h7++;
            case 8 -> h8++; case 9 -> h9++; case 10 -> h10++; case 11 -> h11++;
            case 12 -> h12++; case 13 -> h13++; case 14 -> h14++; case 15 -> h15++;
            case 16 -> h16++; case 17 -> h17++; case 18 -> h18++; case 19 -> h19++;
            case 20 -> h20++; case 21 -> h21++; case 22 -> h22++; case 23 -> h23++;
        }
    }
    // collect individual fields back into the array for output
    private void fillArr() {
        times[0]=h0; times[1]=h1; times[2]=h2; times[3]=h3; times[4]=h4; times[5]=h5;
        times[6]=h6; times[7]=h7; times[8]=h8; times[9]=h9; times[10]=h10; times[11]=h11;
        times[12]=h12; times[13]=h13; times[14]=h14; times[15]=h15; times[16]=h16;
        times[17]=h17; times[18]=h18; times[19]=h19; times[20]=h20; times[21]=h21;
        times[22]=h22; times[23]=h23;
    }
}