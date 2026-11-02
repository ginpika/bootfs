package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.service.ai.AiMetadataService;
import java.io.*;
import java.nio.file.*;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MediaMetadataSecurityTest {
    @TempDir Path root;
    Path png(byte[] text, int declaredLength) throws IOException {
        Path path = root.resolve("test.png");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.write(new byte[]{(byte)137,80,78,71,13,10,26,10}); out.writeInt(declaredLength); out.writeBytes("zTXt"); out.write(text); out.writeInt(0);
            out.writeInt(0); out.writeBytes("IEND"); out.writeInt(0);
        }
        return path;
    }
    @Test @Timeout(3) void enormousDeclaredChunkCannotAllocateAnEnormousArray() throws Exception {
        assertNull(new AiMetadataService().extract(png(new byte[1], Integer.MAX_VALUE).toString()));
    }
    @Test @Timeout(3) void compressedMetadataCannotExpandPastTheMemoryBudget() throws Exception {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(); chunk.write("parameters".getBytes()); chunk.write(0); chunk.write(0);
        try (DeflaterOutputStream zip = new DeflaterOutputStream(chunk)) {
            byte[] block = new byte[8192];
            for (int n = 0; n < 1024; n++) zip.write(block);
        }
        assertNull(new AiMetadataService().extract(png(chunk.toByteArray(), chunk.size()).toString()));
    }
}
