package com.neocopier.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class LogService {

    private static final String LOG_FILE_PATH = "./data/app.log";

    public List<String> readLastLogLines(int maxLines) {
        File file = new File(LOG_FILE_PATH);
        if (!file.exists()) {
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = file.length();
            if (length == 0) return lines;

            long pos = length - 1;
            int lineCount = 0;
            StringBuilder sb = new StringBuilder();

            while (pos >= 0 && lineCount < maxLines) {
                raf.seek(pos);
                char c = (char) raf.readByte();
                if (c == '\n') {
                    if (!sb.isEmpty()) {
                        lines.add(sb.reverse().toString());
                        sb.setLength(0);
                        lineCount++;
                    }
                } else if (c != '\r') {
                    sb.append(c);
                }
                pos--;
            }
            if (!sb.isEmpty() && lineCount < maxLines) {
                lines.add(sb.reverse().toString());
            }
        } catch (IOException e) {
            return List.of("Error reading log file: " + e.getMessage());
        }

        Collections.reverse(lines);
        return lines;
    }

    public File getLogFile() {
        return new File(LOG_FILE_PATH);
    }

    public void clearLogFile() {
        File file = new File(LOG_FILE_PATH);
        if (file.exists()) {
            try {
                Files.write(Path.of(LOG_FILE_PATH), new byte[0]);
            } catch (IOException ignored) {}
        }
    }
}
