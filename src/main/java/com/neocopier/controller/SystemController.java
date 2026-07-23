package com.neocopier.controller;

import com.neocopier.service.FeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class SystemController {

    private final FeedService feedService;

    public SystemController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of("status", "running", "name", "neo-copier-java-backend");
    }

    @GetMapping("/api/system/power")
    public Map<String, Boolean> getPower() {
        return Map.of("powerOn", feedService.isSystemPowerOn());
    }

    @PostMapping("/api/system/power")
    public ResponseEntity<Map<String, Object>> setPower(@RequestBody Map<String, Object> body) {
        if (!(body.get("powerOn") instanceof Boolean powerOn)) {
            return ResponseEntity.badRequest().body(Map.of("error", "powerOn must be a boolean"));
        }
        feedService.setSystemPowerOn(powerOn);
        return ResponseEntity.ok(Map.of("success", true, "powerOn", feedService.isSystemPowerOn()));
    }
}
