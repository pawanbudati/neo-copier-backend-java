package com.neocopier.controller;

import com.neocopier.service.UpstoxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/upstox")
public class UpstoxController {

    private final UpstoxService upstoxService;

    public UpstoxController(UpstoxService upstoxService) {
        this.upstoxService = upstoxService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(upstoxService.getConfigMap());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(upstoxService.getConfigMap());
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, String> body) {
        String apiKey = body.get("apiKey");
        String apiSecret = body.get("apiSecret");
        String redirectUri = body.get("redirectUri");
        String accessToken = body.get("accessToken");

        Map<String, Object> updated = upstoxService.saveConfig(apiKey, apiSecret, redirectUri, accessToken);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, Object>> getAuthUrl() {
        return ResponseEntity.ok(Map.of(
                "authUrl", upstoxService.getAuthUrl(),
                "isConfigured", upstoxService.isConfigured()
        ));
    }

    @GetMapping("/login")
    public ResponseEntity<Void> redirectToLogin() {
        String authUrl = upstoxService.getAuthUrl();
        if (authUrl == null || authUrl.trim().isEmpty()) {
            return ResponseEntity.status(400).build();
        }
        return ResponseEntity.status(302).header("Location", authUrl).build();
    }

    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> getDebug() {
        return ResponseEntity.ok(Map.of(
                "isConfigured", upstoxService.isConfigured(),
                "hasToken", upstoxService.hasValidToken(),
                "apiKeyConfigured", upstoxService.getApiKey() != null && !upstoxService.getApiKey().trim().isEmpty(),
                "redirectUri", upstoxService.getRedirectUri() != null ? upstoxService.getRedirectUri() : "",
                "authUrl", upstoxService.getAuthUrl()
        ));
    }

    @GetMapping("/callback")
    public ResponseEntity<String> handleCallback(@RequestParam(value = "code", required = false) String code,
                                                 @RequestParam(value = "error", required = false) String error) {
        if (error != null) {
            return ResponseEntity.badRequest().body("<html><body><h2 style='color:red;'>Upstox Auth Error: " + error + "</h2></body></html>");
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("<html><body><h2 style='color:red;'>Missing code parameter</h2></body></html>");
        }

        Map<String, Object> result = upstoxService.exchangeCodeForToken(code);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok("<html><body style='font-family:sans-serif;text-align:center;padding-top:50px;background:#090d16;color:#10b981;'>" +
                    "<h2>✓ Upstox Connected Successfully!</h2>" +
                    "<p style='color:#94a3b8;'>Your Upstox access token has been updated. You can now close this tab and return to Neo-Copier.</p>" +
                    "<script>setTimeout(() => window.close(), 3000);</script>" +
                    "</body></html>");
        } else {
            return ResponseEntity.badRequest().body("<html><body style='font-family:sans-serif;text-align:center;padding-top:50px;background:#090d16;color:#f43f5e;'>" +
                    "<h2>✗ Token Exchange Failed</h2>" +
                    "<p style='color:#94a3b8;'>" + result.get("error") + "</p>" +
                    "</body></html>");
        }
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> updateToken(@RequestBody Map<String, String> body) {
        String token = body.get("accessToken");
        if (token == null || token.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "accessToken is required"));
        }
        upstoxService.setAccessToken(token);
        return ResponseEntity.ok(Map.of("success", true, "hasToken", upstoxService.hasValidToken()));
    }
}
