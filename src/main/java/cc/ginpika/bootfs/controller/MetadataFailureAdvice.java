package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.kernel.metadata.MetadataException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class MetadataFailureAdvice {
    @ExceptionHandler(MetadataException.class)
    public ResponseEntity<?> unavailable(MetadataException error) {
        return ResponseEntity.status(503).body(Map.of("error", "Authoritative metadata is unavailable; operation was not acknowledged"));
    }
}
