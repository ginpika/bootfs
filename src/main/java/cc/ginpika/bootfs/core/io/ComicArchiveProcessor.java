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
    public static List<Path> extraction(Path archive) throws IOException {
        List<Path> outputs = new ArrayList<>();
        try (ArchiveInputStream<?> ais = createArchiveStream(archive)) {
            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                // skip .DS_Store or other system files
                if (entry.getName().startsWith(".")) {
                    continue;
                }
                // skip directory entry
                if (entry.isDirectory()) {
                    continue;
                }
                // skip entry without extension
                int pointIdx = entry.getName().lastIndexOf(".");
                if (pointIdx < 1) {
                    continue;
                }
                String ext = entry.getName().substring(pointIdx);
                Path path = Files.createTempFile("tmp" + System.currentTimeMillis(), ext);
                outputs.add(path);
                try (OutputStream output = Files.newOutputStream(path)) { ais.transferTo(output); }
            }
        } catch (Exception e) {
            for (Path path : outputs) {
                try { Files.deleteIfExists(path); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw new IOException("Cannot extract archive", e);
        }
        return outputs;
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