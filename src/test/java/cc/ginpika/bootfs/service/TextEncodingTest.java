package cc.ginpika.bootfs.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TextEncodingTest {
    @Test void automaticDetectionPrefersUtf8AndFallsBackToStrictGbk() {
        String content = "配置：中文与 €\r\n";
        var utf8 = TextEncoding.decode(content.getBytes(StandardCharsets.UTF_8), "auto", null);
        assertEquals("UTF-8", utf8.encoding()); assertEquals(content, utf8.content());
        var gbk = TextEncoding.decode(content.getBytes(Charset.forName("GBK")), null, null);
        assertEquals("GBK", gbk.encoding()); assertEquals(content, gbk.content());
        assertEquals("UTF-8", TextEncoding.decode(new byte[0], null, null).encoding());
    }

    @Test void explicitDecodingOverridesAutomaticAmbiguityAndDurableHints() {
        byte[] ambiguous = {(byte) 0xc2, (byte) 0xa9};
        assertEquals("©", TextEncoding.decode(ambiguous, null, null).content());
        assertEquals("漏", TextEncoding.decode(ambiguous, "gbk", null).content());
        assertEquals("漏", TextEncoding.decode(ambiguous, null, "GBK").content());
        assertEquals("©", TextEncoding.decode(ambiguous, "UTF8", "GBK").content());
        assertEquals("GBK", TextEncoding.decode("ascii".getBytes(StandardCharsets.US_ASCII), null, "GBK").encoding());
        assertEquals("GBK", TextEncoding.decode(new byte[0], null, "GBK").encoding());
    }

    @Test void damagedUtf8BomAndInvalidBytesAreNeverReplacedOrMisidentified() {
        byte[] damagedBom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xd6, (byte) 0xd0};
        assertEquals(415, assertThrows(ResponseStatusException.class, () -> TextEncoding.decode(damagedBom, null, null)).getRawStatusCode());
        for (byte[] invalid : new byte[][]{{(byte) 0xc3, 0x28}, {(byte) 0x80}, {(byte) 0xd6}}) {
            assertEquals(415, assertThrows(ResponseStatusException.class, () -> TextEncoding.decode(invalid, null, null)).getRawStatusCode());
        }
        String bomContent = "\ufeff原始内容\n";
        var decoded = TextEncoding.decode(bomContent.getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals(bomContent, decoded.content());
        assertArrayEquals(bomContent.getBytes(StandardCharsets.UTF_8), TextEncoding.encode(decoded.content(), decoded.encoding()));
    }

    @Test void unsupportedOrUnrepresentableCharactersFailInsteadOfBeingReplaced() {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> TextEncoding.requested("UTF-16")).getRawStatusCode());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> TextEncoding.encode("\ud800", "GBK")).getRawStatusCode());
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> TextEncoding.encode("emoji 😀", "GBK")).getRawStatusCode());
        assertArrayEquals("中文 €".getBytes(Charset.forName("GBK")), TextEncoding.encode("中文 €", "GBK"));
    }
}
