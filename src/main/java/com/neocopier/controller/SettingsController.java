package com.neocopier.controller;

import com.neocopier.model.Settings;
import com.neocopier.service.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Settings getSettings() {
        return settingsService.getSettings();
    }

    @PostMapping
    public Settings updateSettings(@RequestBody Map<String, Object> body) {
        return settingsService.updateSettings(body);
    }
}
