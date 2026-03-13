package cc.ginpika.bootfs.core.io;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class JsonFileLineDeleter {
    public static void deleteLine(String uuid, File file) {
        if (!file.exists()) {
            return;
        }
        String prefix = "\"" + uuid;
        Path idxFile = Path.of(file.getAbsolutePath());
        try {
            BufferedReader reader = Files.newBufferedReader(idxFile);
            // String workDir = String.valueOf(Path.of(idxFile.getParent().toString(), "/temp" + System.currentTimeMillis()));
            Path temp = Files.createTempFile("temp" + System.currentTimeMillis(), null);
            BufferedWriter writer = Files.newBufferedWriter(temp);
            String line, lastLine = "";
            boolean foundLineSkip = false, firstLineFlag = false;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(prefix)) {
                    if (firstLineFlag) {
                        if (foundLineSkip) {
                            foundLineSkip = false;
                        } else {
                            writer.write(lastLine);
                        }
                    }
                    lastLine = line;
                    if (!line.startsWith("}") && firstLineFlag) {
                        writer.newLine();
                    }
                    firstLineFlag = true;
                } else {
                    if (line.endsWith("}")) {
                        if (!lastLine.startsWith("{")) {
                            lastLine = lastLine.substring(0, lastLine.lastIndexOf(","));
                        }
                    }
                    writer.write(lastLine);
                    foundLineSkip = true;
                }
            }
            writer.newLine();
            writer.write(lastLine);
            reader.close();
            writer.close();
            Files.delete(idxFile);
            Files.move(temp, idxFile);
        } catch (IOException e) {
            log.error("JsonFileLineDeleter error", e);
        }
    }
}
