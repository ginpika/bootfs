package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.s3.S3CryptoUtils;
import cc.ginpika.bootfs.s3.S3Config;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.HttpMethod;
import java.net.URI;
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
    @Autowired S3Config config;
    private MockHttpServletRequestBuilder signed(String method,String path,byte[] body,String type,String declared) {
        URI uri = URI.create(path);
        String query = uri.getRawQuery() == null ? "" : java.util.Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.contains("=") ? pair : pair + "=").sorted().collect(java.util.stream.Collectors.joining("&"));
        String stamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        String date=stamp.substring(0,8), hash=declared==null?S3CryptoUtils.sha256Hex(body):declared;
        String headers="host;x-amz-content-sha256;x-amz-date", scope=date+"/us-east-1/s3/aws4_request";
        String canonical=method+"\n"+uri.getRawPath()+"\n"+query+"\n"+"host:localhost\nx-amz-content-sha256:"+hash+"\nx-amz-date:"+stamp+"\n\n"+headers+"\n"+hash;
        byte[] key=("AWS4test-secret").getBytes(StandardCharsets.UTF_8);
        for(String part:new String[]{date,"us-east-1","s3","aws4_request"}) key=S3CryptoUtils.hmacSha256(key,part);
        String signature=S3CryptoUtils.hmacSha256Hex(key,"AWS4-HMAC-SHA256\n"+stamp+"\n"+scope+"\n"+S3CryptoUtils.sha256Hex(canonical));
        return request(HttpMethod.valueOf(method),path).content(body).contentType(type).header("Host","localhost")
                .header("x-amz-date",stamp).header("x-amz-content-sha256",hash)
                .header("Authorization","AWS4-HMAC-SHA256 Credential=test/"+scope+", SignedHeaders="+headers+", Signature="+signature);
    }
    @Test void bucketLocationSupportsBothQueryFormsAndTrailingSlashes() throws Exception {
        var namespaces = java.util.Map.of("s3", "http://s3.amazonaws.com/doc/2006-03-01/");
        for (String path : new String[]{"/s3/bootfs?location", "/s3/bootfs?location=", "/s3/bootfs/?location", "/s3/bootfs/?location="}) {
            mvc.perform(signed("GET",path,new byte[0],"application/octet-stream",null))
                    .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/xml"))
                    .andExpect(xpath("/s3:LocationConstraint", namespaces).exists())
                    .andExpect(xpath("/s3:LocationConstraint", namespaces).string(""))
                    .andExpect(xpath("//*[local-name()='ListBucketResult']").doesNotExist());
        }
    }
    @Test void headBucketAndLocationReportTheConfiguredRegion() throws Exception {
        for (String path : new String[]{"/s3/bootfs", "/s3/bootfs/"}) {
            mvc.perform(signed("HEAD",path,new byte[0],"application/octet-stream",null))
                    .andExpect(status().isOk()).andExpect(header().string("x-amz-bucket-region", "us-east-1"))
                    .andExpect(content().string(""));
        }
        String original = config.getRegion();
        try {
            config.setRegion("eu-west-1");
            mvc.perform(signed("GET","/s3/bootfs?location",new byte[0],"application/octet-stream",null))
                    .andExpect(status().isOk())
                    .andExpect(xpath("/*[local-name()='LocationConstraint']").string("eu-west-1"));
            mvc.perform(signed("HEAD","/s3/bootfs",new byte[0],"application/octet-stream",null))
                    .andExpect(status().isOk()).andExpect(header().string("x-amz-bucket-region", "eu-west-1"))
                    .andExpect(content().string(""));
        } finally {
            config.setRegion(original);
        }
    }
    @Test void bucketDiscoveryRequiresASignatureAndAnExistingBucket() throws Exception {
        mvc.perform(get("/s3/bootfs?location")).andExpect(status().isForbidden())
                .andExpect(xpath("/Error/Code").string("AccessDenied"));
        mvc.perform(head("/s3/bootfs")).andExpect(status().isForbidden());
        mvc.perform(signed("GET","/s3/missing?location",new byte[0],"application/octet-stream",null))
                .andExpect(status().isNotFound()).andExpect(xpath("/Error/Code").string("NoSuchBucket"));
        mvc.perform(signed("HEAD","/s3/missing",new byte[0],"application/octet-stream",null))
                .andExpect(status().isNotFound());
    }
    @Test void aLocationQueryOnAnObjectDoesNotBecomeBucketDiscovery() throws Exception {
        String path = "/s3/bootfs/location";
        byte[] body = "location object".getBytes(StandardCharsets.UTF_8);
        mvc.perform(signed("PUT",path,body,"application/octet-stream",null)).andExpect(status().isOk());
        mvc.perform(signed("GET",path+"?location",new byte[0],"application/octet-stream",null))
                .andExpect(status().isOk()).andExpect(content().bytes(body));
    }
    @Test void bucketListingAndAuthenticationErrorsHaveReadableXmlBodies() throws Exception {
        mvc.perform(signed("GET","/s3",new byte[0],"application/octet-stream",null))
                .andExpect(status().isOk()).andExpect(xpath("/ListAllMyBucketsResult/Buckets/Bucket/Name").string("bootfs"));
        for (String path : new String[]{"/s3", "/s3/bootfs"}) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/xml"))
                    .andExpect(xpath("/Error/Code").string("AccessDenied"));
        }
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
