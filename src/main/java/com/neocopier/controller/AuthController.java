package com.neocopier.controller;

import com.neocopier.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (authService.validatePassword(password)) {
            return ResponseEntity.ok(Map.of("success", true, "token", authService.getCurrentSessionToken()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid password"));
    }
}
