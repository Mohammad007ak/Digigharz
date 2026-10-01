package ir.digigharz.web;

import ir.digigharz.HttpError;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors as {error, ...extra}, in Persian, the way the app expects them. */
@RestControllerAdvice
public class ErrorAdvice {
  @ExceptionHandler(HttpError.class)
  public ResponseEntity<Map<String, Object>> http(HttpError e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", e.getMessage());
    body.putAll(e.extra());
    return ResponseEntity.status(e.status()).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> other(Exception e) {
    e.printStackTrace();
    return ResponseEntity.status(500).body(Map.of("error", "خطای داخلی سرور."));
  }
}
