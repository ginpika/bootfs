package cc.ginpika.bootfs.core.io;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class JsonFileAppender {
    private static final int LINE_SEPARATOR_LENGTH = System.lineSeparator().length();

    // TODO use nio2 instead of RandomAccessFile, it`s deprecated（FileChannel）
    // TODO refactor this, use line_separator_length to find position is danger e.g. migration from Unix to Windows
    public static void append(File file, String uuid, JSONObject json) {
        if (!file.exists()) {
            return;
        }
        if (file.length() <= 4) {
            try (RandomAccessFile raf = new RandomAccessFile(file.getAbsolutePath(), "rw")) {
                raf.setLength(raf.length() - 1);
                raf.seek(raf.length());
                raf.write(("\"" + uuid + "\":" + json.toJSONString() + System.lineSeparator() + "}").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.getAbsolutePath(), "rw")) {
            raf.setLength(raf.length() - LINE_SEPARATOR_LENGTH);
            raf.seek(raf.length() - 1);
            raf.write(("," + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            raf.write(("\"" + uuid + "\":" + json.toJSONString() + System.lineSeparator() + "}").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeEmptyJson(Path dbJson) {
        try (BufferedWriter writer = Files.newBufferedWriter(dbJson)) {
            writer.write("{" + System.lineSeparator() +"}");
        } catch (IOException e) {
            log.error("Meet problem when JsonFileAppender try to write a empty json to empty db.json", e);
        }
    }
}
