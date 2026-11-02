package cc.ginpika.bootfs.core.io;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.*;
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ComicArchiveProcessor {
    private static final int MAX_ENTRIES = 10000;
    private static final long MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    public static List<Path> extraction(Path archive) throws IOException {
        List<Path> outputs = new ArrayList<>();
        try (ArchiveInputStream<?> ais = createArchiveStream(archive)) {
            ArchiveEntry entry;
            int entries = 0;
            long total = 0;
            while ((entry = ais.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES || entry.getSize() > MAX_ENTRY_BYTES) throw new IOException("Archive exceeds extraction limits");
                // skip .DS_Store or other system files
                if (entry.getName().startsWith(".")) {
                    total += copyEntry(ais, OutputStream.nullOutputStream(), MAX_TOTAL_BYTES - total);
                    continue;
                }
                // skip directory entry
                if (entry.isDirectory()) {
                    total += copyEntry(ais, OutputStream.nullOutputStream(), MAX_TOTAL_BYTES - total);
                    continue;
                }
                // skip entry without extension
                int pointIdx = entry.getName().lastIndexOf(".");
                if (pointIdx < 1) {
                    total += copyEntry(ais, OutputStream.nullOutputStream(), MAX_TOTAL_BYTES - total);
                    continue;
                }
                String ext = entry.getName().substring(pointIdx);
                if (!ext.matches("\\.[A-Za-z0-9]{1,16}")) throw new IOException("Invalid archive entry extension");
                Path path = Files.createTempFile("tmp" + System.currentTimeMillis(), ext);
                outputs.add(path);
                try (OutputStream output = Files.newOutputStream(path)) {
                    total += copyEntry(ais, output, MAX_TOTAL_BYTES - total);
                }
            }
        } catch (Exception e) {
            for (Path path : outputs) {
                try { Files.deleteIfExists(path); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw new IOException("Cannot extract archive", e);
        }
        return outputs;
    }

    private static long copyEntry(InputStream input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[8192];
        long size = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            size += read;
            if (size > MAX_ENTRY_BYTES || size > remaining) throw new IOException("Archive exceeds extraction limits");
            output.write(buffer, 0, read);
        }
        return size;
    }

    private static ArchiveInputStream<?> createArchiveStream(Path archive)
            throws IOException {
        InputStream in = Files.newInputStream(archive);
        String name = archive.toFile().getName().toLowerCase();
        if (name.endsWith(".zip")) {
            return new ZipArchiveInputStream(in);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return new TarArchiveInputStream(new GzipCompressorInputStream(in));
        } else if (name.endsWith(".tar")) {
            return new TarArchiveInputStream(in);
        } else if (name.endsWith(".jar")) {
            return new JarArchiveInputStream(in);
        } else if (name.endsWith(".7z")) {
            return new ArchiveStreamFactory().createArchiveInputStream(in);
        }
        in.close();
        throw new IOException("Unsupported archive: " + name);
    }
}
