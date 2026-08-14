package com.flatmaite.common.web;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Uniform error envelope: {"error": {"code", "message", "fieldErrors?"}}. */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
    return envelope(e.getStatus(), e.getCode(), e.getMessage(), null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
    Map<String, String> fieldErrors = new HashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
    return envelope(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed", fieldErrors);
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadable(
      org.springframework.http.converter.HttpMessageNotReadableException e) {
    return envelope(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request body", null);
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<Map<String, Object>> handleNoResource(
      org.springframework.web.servlet.resource.NoResourceFoundException e) {
    return envelope(HttpStatus.NOT_FOUND, "not_found", "No such endpoint", null);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
    log.error("Unhandled exception", e);
    return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong", null);
  }

  private ResponseEntity<Map<String, Object>> envelope(
      HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
    Map<String, Object> error = new HashMap<>();
    error.put("code", code);
    error.put("message", message);
    if (fieldErrors != null && !fieldErrors.isEmpty()) {
      error.put("fieldErrors", fieldErrors);
    }
    return ResponseEntity.status(status).body(Map.of("error", error));
  }
}
