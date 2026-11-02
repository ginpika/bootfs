package cc.ginpika.bootfs.config;

import com.fasterxml.jackson.core.*;
import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Runs after authentication and before object construction, including chunked requests. */
@ControllerAdvice
public class RequestBodyLimits extends RequestBodyAdviceAdapter {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    @Override public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) { return true; }

    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type type,
                                                    Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes = message.getBody().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds limit");
        MediaType mime = message.getHeaders().getContentType();
        if (parameter.getParameterType() == String.class || mime != null && (mime.getSubtype().equals("json") || mime.getSubtype().endsWith("+json"))) {
            int depth = 0, tokens = 0;
            boolean completed = false;
            try (JsonParser parser = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).createParser(bytes)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (completed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Multiple JSON values are not allowed");
                    if (++tokens > 250000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "JSON exceeds complexity limit");
                    if ((token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) && ++depth > 64)
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON nesting exceeds limit");
                    if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) depth--;
                    if (depth == 0) completed = true;
                }
            } catch (JsonProcessingException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON"); }
        }
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            @Override public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }
}
