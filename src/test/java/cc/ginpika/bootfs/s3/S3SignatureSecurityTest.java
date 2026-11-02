package cc.ginpika.bootfs.s3;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class S3SignatureSecurityTest {
    final S3Config config = config();
    final S3SigV4Verifier verifier = new S3SigV4Verifier(config);
    static S3Config config() {
        S3Config value = new S3Config(); value.setAccessKey("test"); value.setSecretKey("secret"); return value;
    }
    MockHttpServletRequest signed(String canonicalQuery, String actualQuery, String headers) {
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        String date = stamp.substring(0, 8), scope = date + "/us-east-1/s3/aws4_request";
        String hash = S3CryptoUtils.sha256Hex(new byte[0]);
        StringBuilder canonicalHeaders = new StringBuilder();
        for (String header : headers.split(";")) canonicalHeaders.append(header).append(':')
                .append(header.equals("host") ? "localhost" : header.equals("x-amz-date") ? stamp : hash).append('\n');
        String canonical = "GET\n/s3/bootfs\n" + canonicalQuery + "\n" + canonicalHeaders + "\n" + headers + "\n" + hash;
        byte[] key = S3CryptoUtils.hmacSha256("AWS4secret".getBytes(StandardCharsets.UTF_8), date);
        key = S3CryptoUtils.hmacSha256(key, "us-east-1"); key = S3CryptoUtils.hmacSha256(key, "s3"); key = S3CryptoUtils.hmacSha256(key, "aws4_request");
        String signature = S3CryptoUtils.hmacSha256Hex(key, "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n" + S3CryptoUtils.sha256Hex(canonical));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/s3/bootfs");
        request.setQueryString(actualQuery); request.addHeader("host", "localhost"); request.addHeader("x-amz-date", stamp);
        request.addHeader("x-amz-content-sha256", hash);
        request.addHeader("Authorization", "AWS4-HMAC-SHA256 Credential=test/" + scope + ", SignedHeaders=" + headers + ", Signature=" + signature);
        return request;
    }
    @Test void duplicateQueryParametersCannotBePrependedWithoutInvalidatingASignature() {
        String headers = "host;x-amz-content-sha256;x-amz-date";
        assertNull(verifier.verify(signed("prefix=public", "prefix=public", headers)));
        assertEquals(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH, verifier.verify(signed("prefix=public", "prefix=private&prefix=public", headers)));
        assertNull(verifier.verify(signed("prefix=private&prefix=public", "prefix=public&prefix=private", headers)));
    }
    @Test void hostAndDateAreMandatorySignedHeaders() {
        assertEquals(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH, verifier.verify(signed("", "", "x-amz-content-sha256;x-amz-date")));
        assertEquals(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH, verifier.verify(signed("", "", "host;x-amz-content-sha256")));
    }
}
