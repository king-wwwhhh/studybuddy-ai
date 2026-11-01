package org.example.controller;

import org.example.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(AuthService.AuthException.class)
    public ResponseEntity<?> handle(AuthService.AuthException error) {
        return ResponseEntity.status(error.status).body(java.util.Map.of(
                "success", false, "code", error.status, "message", error.getMessage()));
    }
}
