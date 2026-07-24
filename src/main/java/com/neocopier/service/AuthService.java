package com.neocopier.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Value("${admin.password:admin}")
    private String adminPasswordConfig;

    @Value("${auth.enabled:false}")
    private boolean authEnabled;

    private String activeAdminPassword;
    private final String currentSessionToken = UUID.randomUUID().toString();

    @PostConstruct
    public void initAdminPassword() {
        this.activeAdminPassword = adminPasswordConfig;
        String envPassword = readAdminPasswordFromDotEnv();
        if (envPassword != null && !envPassword.trim().isEmpty()) {
            this.activeAdminPassword = envPassword.trim();
            log.info("[Auth] Admin password loaded from .env file.");
        }
        if (!authEnabled) {
            log.info("[Auth] Dev environment detected: Login authentication is DISABLED.");
        }
    }

    public boolean isAuthDisabled() {
        return !authEnabled;
    }

    public boolean validatePassword(String password) {
        if (!authEnabled) {
            return true;
        }
        return activeAdminPassword != null && activeAdminPassword.equals(password);
    }

    public String getCurrentSessionToken() {
        return currentSessionToken;
    }

    public boolean validateSessionToken(String token) {
        if (!authEnabled) {
            return true;
        }
        return token != null && token.equals(currentSessionToken);
    }

    private String readAdminPasswordFromDotEnv() {
        File[] possibleEnvFiles = new File[]{
                new File(".env"),
                new File("../.env"),
                new File("../neo-copier-backend-py/.env")
        };

        for (File file : possibleEnvFiles) {
            if (file.exists()) {
                try {
                    List<String> lines = Files.readAllLines(file.toPath());
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("ADMIN_PASSWORD=")) {
                            String value = trimmed.substring("ADMIN_PASSWORD=".length()).trim();
                            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                                value = value.substring(1, value.length() - 1);
                            }
                            return value;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
