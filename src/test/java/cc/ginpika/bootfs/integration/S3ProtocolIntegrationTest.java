package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.s3.S3CryptoUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real servlet filters, SigV4 and byte publication; complements lifecycle tests with filters disabled. */
@SpringBootTest(properties={"sso.enabled=false","s3.enabled=true","s3.access-key=test","s3.secret-key=test-secret"})
@AutoConfigureMockMvc
@DirtiesContext
class S3ProtocolIntegrationTest {
    static final Path ROOT = MononodeIntegrationTest.tempRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("tfs.root-path",ROOT::toString); }
    @Autowired MockMvc mvc;
    private MockHttpServletRequestBuilder signed(String method,String path,byte[] body,String type,String declared) {
        String stamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        String date=stamp.substring(0,8), hash=declared==null?S3CryptoUtils.sha256Hex(body):declared;
        String headers="host;x-amz-content-sha256;x-amz-date", scope=date+"/us-east-1/s3/aws4_request";
        String canonical=method+"\n"+path+"\n\n"+"host:localhost\nx-amz-content-sha256:"+hash+"\nx-amz-date:"+stamp+"\n\n"+headers+"\n"+hash;
        byte[] key=("AWS4test-secret").getBytes(StandardCharsets.UTF_8);
        for(String part:new String[]{date,"us-east-1","s3","aws4_request"}) key=S3CryptoUtils.hmacSha256(key,part);
        String signature=S3CryptoUtils.hmacSha256Hex(key,"AWS4-HMAC-SHA256\n"+stamp+"\n"+scope+"\n"+S3CryptoUtils.sha256Hex(canonical));
        return (method.equals("PUT")?put(path):get(path)).content(body).contentType(type).header("Host","localhost")
                .header("x-amz-date",stamp).header("x-amz-content-sha256",hash)
                .header("Authorization","AWS4-HMAC-SHA256 Credential=test/"+scope+", SignedHeaders="+headers+", Signature="+signature);
    }
    @Test void formTypedS3ObjectRetainsExactBytesThroughTheWholeFilterChain() throws Exception {
        byte[] body="raw=one&raw=two&encoded=%00%FF".getBytes(StandardCharsets.UTF_8); String path="/s3/bootfs/raw-form";
        mvc.perform(signed("PUT",path,body,"application/x-www-form-urlencoded",null)).andExpect(status().isOk());
        mvc.perform(signed("GET",path,new byte[0],"application/octet-stream",null)).andExpect(status().isOk()).andExpect(content().bytes(body));
        mvc.perform(get(path)).andExpect(status().isForbidden());
    }
    @Test void signedButCorruptedPayloadCannotReplaceThePreviousObject() throws Exception {
        byte[] original="original".getBytes(StandardCharsets.UTF_8), changed="modified".getBytes(StandardCharsets.UTF_8); String path="/s3/bootfs/protected";
        mvc.perform(signed("PUT",path,original,"application/octet-stream",null)).andExpect(status().isOk());
        mvc.perform(signed("PUT",path,changed,"application/octet-stream",S3CryptoUtils.sha256Hex(original))).andExpect(status().isBadRequest());
        mvc.perform(signed("GET",path,new byte[0],"application/octet-stream",null)).andExpect(status().isOk()).andExpect(content().bytes(original));
    }
}
