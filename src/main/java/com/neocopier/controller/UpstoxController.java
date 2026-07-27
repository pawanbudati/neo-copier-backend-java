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

    @GetMapping("/test-candle")
    public ResponseEntity<Map<String, Object>> testCandle(@RequestParam(value = "key", defaultValue = "NSE_INDEX|Nifty 50") String key) {
        return ResponseEntity.ok(upstoxService.testHistoricalCandles(key));
    }

    @GetMapping("/callback")
    public ResponseEntity<String> handleCallback(@RequestParam(value = "code", required = false) String code,
                                                 @RequestParam(value = "error", required = false) String error) {
        if (error != null) {
            return ResponseEntity.badRequest().body(buildCallbackHtml(false, "Upstox Auth Error: " + error));
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(buildCallbackHtml(false, "Missing authorization code parameter"));
        }

        Map<String, Object> result = upstoxService.exchangeCodeForToken(code);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(buildCallbackHtml(true, "Your Upstox Access Token has been authenticated and saved. Real historical chart engine is now active."));
        } else {
            return ResponseEntity.badRequest().body(buildCallbackHtml(false, "Token Exchange Failed: " + result.get("error")));
        }
    }

    private String buildCallbackHtml(boolean isSuccess, String message) {
        String icon = isSuccess ? "✓" : "✕";
        String iconClass = isSuccess ? "" : "icon-error";
        String title = isSuccess ? "Upstox Connected!" : "Connection Failed";
        String primaryColor = isSuccess ? "#10b981" : "#f43f5e";

        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<title>Upstox Authentication | Neo-Copier</title><style>" +
                "* { box-sizing: border-box; margin: 0; padding: 0; }" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: radial-gradient(circle at top, #1e293b 0%, #0f172a 60%, #020617 100%); color: #f8fafc; min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }" +
                ".card { background: rgba(30, 41, 59, 0.75); backdrop-filter: blur(16px); border: 1px solid rgba(255, 255, 255, 0.1); border-radius: 20px; padding: 40px 32px; max-width: 480px; width: 100%; text-align: center; box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5); }" +
                ".icon-circle { width: 72px; height: 72px; border-radius: 50%; background: rgba(16, 185, 129, 0.15); border: 2px solid " + primaryColor + "; color: " + primaryColor + "; display: flex; align-items: center; justify-content: center; font-size: 36px; margin: 0 auto 24px; box-shadow: 0 0 20px " + (isSuccess ? "rgba(16, 185, 129, 0.3)" : "rgba(244, 63, 94, 0.3)") + "; }" +
                ".icon-error { background: rgba(244, 63, 94, 0.15); }" +
                "h2 { font-size: 24px; font-weight: 700; margin-bottom: 12px; color: " + primaryColor + "; }" +
                "p { color: #94a3b8; font-size: 15px; line-height: 1.6; margin-bottom: 28px; }" +
                ".btn-group { display: flex; flex-direction: column; gap: 12px; }" +
                ".btn { display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 14px 24px; border-radius: 12px; font-size: 15px; font-weight: 600; text-decoration: none; cursor: pointer; transition: all 0.2s ease; border: none; }" +
                ".btn-primary { background: linear-gradient(135deg, " + (isSuccess ? "#10b981 0%, #059669 100%" : "#f43f5e 0%, #e11d48 100%") + "); color: #ffffff; box-shadow: 0 4px 14px " + (isSuccess ? "rgba(16, 185, 129, 0.4)" : "rgba(244, 63, 94, 0.4)") + "; }" +
                ".btn-primary:hover { transform: translateY(-2px); filter: brightness(1.1); }" +
                ".btn-secondary { background: rgba(255, 255, 255, 0.05); color: #cbd5e1; border: 1px solid rgba(255, 255, 255, 0.15); }" +
                ".btn-secondary:hover { background: rgba(255, 255, 255, 0.1); color: #ffffff; }" +
                ".timer-text { margin-top: 20px; font-size: 13px; color: #64748b; }" +
                "</style></head><body><div class='card'>" +
                "<div class='icon-circle " + iconClass + "'>" + icon + "</div>" +
                "<h2>" + title + "</h2>" +
                "<p>" + message + "</p>" +
                "<div class='btn-group'>" +
                "<a href='https://pawanbudati.github.io/' class='btn btn-primary'>🚀 Return to Dashboard</a>" +
                "<button onclick='if(history.length > 1) history.back(); else window.location.href=\"https://pawanbudati.github.io/\";' class='btn btn-secondary'>⬅ Go Back</button>" +
                "</div>" +
                (isSuccess ? "<div class='timer-text' id='timer'>Auto-redirecting to dashboard in <span id='count'>5</span>s...</div>" : "") +
                "</div>" +
                (isSuccess ? "<script>let s=5; setInterval(()=>{ s--; if(document.getElementById('count')) document.getElementById('count').innerText=s; if(s<=0) window.location.href='https://pawanbudati.github.io/'; }, 1000);</script>" : "") +
                "</body></html>";
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
