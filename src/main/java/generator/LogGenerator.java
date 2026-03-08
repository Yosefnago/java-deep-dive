package generator;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class LogGenerator {

    record LogTemplate(String level, String[] messages) {}

    public static void main(String[] args) {
        String fileName = "input.txt";
        int linesCount = 1_000_000;

        // Template defining
        LogTemplate[] templates = {
                new LogTemplate("INFO", new String[]{"User login successful", "System heartbeat pulse", "Data sync complete"}),
                new LogTemplate("WARN", new String[]{"Disk space running low", "High memory usage detected", "Slow response time"}),
                new LogTemplate("ERROR", new String[]{"User login failed", "Database connection reset", "Invalid token detected"})
        };

        Random random = new Random();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        System.out.println("Generating 1,000,000 realistic log lines...");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            for (int i = 0; i < linesCount; i++) {
                // Random template [INFO, WARN, ERROR]
                LogTemplate template = templates[random.nextInt(templates.length)];

                // Random message for the log level
                String message = template.messages()[random.nextInt(template.messages().length)];

                // Random date and time
                String timestamp = LocalDateTime.now().minusSeconds(random.nextInt(86400)).format(dtf);

                writer.write(timestamp + " [" + template.level() + "] " + message);
                writer.newLine();
            }
            System.out.println("Finished! File 'input.txt' is ready.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
