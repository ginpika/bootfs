package cc.ginpika.bootfs.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Strict source decoding and lossless target encoding for the supported text formats. */
public final class TextEncoding {
    public static final String UTF8 = "UTF-8";
    public static final String GBK = "GBK";
    private static final Charset GBK_CHARSET = Charset.forName(GBK);

    private TextEncoding() {}

    public record Decoded(String content, String encoding) {}

    /** Null means automatic detection; unknown encodings are never silently accepted. */
    public static String requested(String encoding) {
        if (encoding == null || encoding.isBlank() || "auto".equalsIgnoreCase(encoding.trim())) return null;
        return switch (encoding.trim().toUpperCase(Locale.ROOT)) {
            case "UTF-8", "UTF8" -> UTF8;
            case GBK -> GBK;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持自动识别、UTF-8 或 GBK 编码");
        };
    }

    public static String hint(String encoding) {
        if (UTF8.equals(encoding) || GBK.equals(encoding)) return encoding;
        return null;
    }

    public static Decoded decode(byte[] bytes, String requested, String hint) {
        String selected = requested(requested);
        if (selected == null) selected = hint(hint);
        if (selected != null) {
            try { return new Decoded(decode(bytes, charset(selected)), selected); }
            catch (CharacterCodingException invalid) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件无法按 " + selected + " 编码读取");
            }
        }
        try { return new Decoded(decode(bytes, StandardCharsets.UTF_8), UTF8); }
        catch (CharacterCodingException invalidUtf8) {
            // A UTF-8 BOM is authoritative, even when the following bytes are damaged.
            if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UTF-8 文件内容已损坏");
            }
            try { return new Decoded(decode(bytes, GBK_CHARSET), GBK); }
            catch (CharacterCodingException invalidGbk) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件不是有效的 UTF-8 或 GBK 文本");
            }
        }
    }

    public static byte[] encode(String content, String encoding) {
        String selected = requested(encoding);
        if (selected == null) selected = UTF8;
        try {
            ByteBuffer encoded = charset(selected).newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(content));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (MalformedInputException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本包含无效的 Unicode 字符");
        } catch (CharacterCodingException unmappable) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "文本包含 " + selected + " 无法表示的字符，请删除这些字符或改用 UTF-8 编码");
        }
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static Charset charset(String encoding) { return GBK.equals(encoding) ? GBK_CHARSET : StandardCharsets.UTF_8; }
}
