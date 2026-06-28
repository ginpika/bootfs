package cc.ginpika.bootfs.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SigV4 所需的哈希/HMAC/hex 工具。基于 javax.crypto + java.util,无外部依赖。
 */
public final class S3CryptoUtils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private S3CryptoUtils() {}

    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String data) {
        return hex(sha256(data.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(byte[] data) {
        return hex(sha256(data));
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmacSha256(byte[] key, String data) {
        return hmacSha256(key, data.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256Hex(byte[] key, String data) {
        return hex(hmacSha256(key, data));
    }

    public static String md5Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
