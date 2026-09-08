package com.refundops;

import java.util.Map;
import javax.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    public static Map<String, String> body(String code, String message) {
        return Map.of("message", message, "code", code);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> applicationError(ApiException error) {
        return ResponseEntity.status(error.getStatus()).body(body(error.getCode(), error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .sorted().findFirst().orElse("Invalid request.");
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> invalidConstraint(ConstraintViolationException error) {
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", "Request validation failed."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableRequest(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(body("INVALID_JSON",
                "Invalid JSON request. Use a supported reason code and valid field types."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedMedia(HttpMediaTypeNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(body("UNSUPPORTED_MEDIA_TYPE", "Use application/json for API request bodies."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupportedMethod(HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body("METHOD_NOT_ALLOWED", "This HTTP method is not supported."));
    }
}
