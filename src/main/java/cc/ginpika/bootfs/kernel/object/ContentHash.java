package cc.ginpika.bootfs.kernel.object;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

public final class ContentHash {
    private ContentHash() {}
    public static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) { return sha256(input); }
    }
    public static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream content = new DigestInputStream(input, digest)) { content.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
