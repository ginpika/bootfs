package com.nihilx.tfs.core.io;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class JsonFileLineUpdater {
    public static void updateLine(Path file, String uuid, JSONObject fileJson) {
        if (!Files.exists(file)) {
            return;
        }
        String prefix = "\"" + uuid;
        try(BufferedReader reader = Files.newBufferedReader(file);) {
            Path temp = Files.createTempFile("temp" + System.currentTimeMillis(), null);
            BufferedWriter writer = Files.newBufferedWriter(temp);
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(prefix)) {
                    writer.write(line);
                    if (!line.startsWith("}")) {
                        writer.newLine();
                    }
                } else {
                    writer.write("\"" + uuid + "\"" + ":" + fileJson.toJSONString()
                            + (line.endsWith(",") ? "," + System.lineSeparator() : System.lineSeparator()));
                }
            }
            writer.close();
            Files.delete(file);
            Files.move(temp, file);
        } catch (IOException e) {
            log.error("JsonFileLineUpdater Error", e);
        }
    }
}
