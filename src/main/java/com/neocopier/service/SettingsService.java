package com.neocopier.service;

import com.neocopier.model.Settings;
import com.neocopier.repository.SettingsRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SettingsService {

    private final SettingsRepository settingsRepository;

    public SettingsService(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public Settings getSettings() {
        return settingsRepository.findById(1).orElseGet(() -> {
            Settings defaultSettings = Settings.builder().id(1).autoReplicate(true).autoRenewSessions(true).build();
            return settingsRepository.save(defaultSettings);
        });
    }

    public Settings updateSettings(Map<String, Object> updates) {
        Settings current = getSettings();
        if (updates.containsKey("autoReplicate") && updates.get("autoReplicate") instanceof Boolean val) {
            current.setAutoReplicate(val);
        }
        if (updates.containsKey("autoRenewSessions") && updates.get("autoRenewSessions") instanceof Boolean val) {
            current.setAutoRenewSessions(val);
        }
        return settingsRepository.save(current);
    }
}
