package com.neocopier.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${DATABASE_URL:}")
    private String databaseUrlEnv;

    @Bean
    @Primary
    public DataSource dataSource() {
        String dbUrl = databaseUrlEnv;

        // Try reading .env file if DATABASE_URL environment variable is empty
        if (dbUrl == null || dbUrl.trim().isEmpty()) {
            dbUrl = readDatabaseUrlFromDotEnv();
        }

        if (dbUrl != null && (dbUrl.startsWith("postgresql://") || dbUrl.startsWith("postgres://"))) {
            log.info("[Database] Parsing PostgreSQL URI format: {}", hidePassword(dbUrl));
            try {
                URI uri = new URI(dbUrl.replace("postgresql://", "http://").replace("postgres://", "http://"));
                String host = uri.getHost();
                int port = uri.getPort() != -1 ? uri.getPort() : 5432;
                String path = uri.getPath();

                String userInfo = uri.getUserInfo();
                String username = "";
                String password = "";

                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    password = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }

                String jdbcUrl = String.format("jdbc:postgresql://%s:%d%s", host, port, path);
                log.info("[Database] Connecting to PostgreSQL at {}", jdbcUrl);

                return DataSourceBuilder.create()
                        .driverClassName("org.postgresql.Driver")
                        .url(jdbcUrl)
                        .username(username)
                        .password(password)
                        .build();

            } catch (Exception e) {
                log.error("[Database] Failed to parse PostgreSQL URL, falling back: {}", e.getMessage());
            }
        }

        if (dbUrl != null && dbUrl.startsWith("jdbc:postgresql://")) {
            log.info("[Database] Using JDBC PostgreSQL URL: {}", dbUrl);
            return DataSourceBuilder.create()
                    .driverClassName("org.postgresql.Driver")
                    .url(dbUrl)
                    .build();
        }

        // Fallback to embedded H2 if no PostgreSQL URL is provided
        log.info("[Database] No external PostgreSQL URL detected. Using local H2 file database.");
        return DataSourceBuilder.create()
                .driverClassName("org.h2.Driver")
                .url("jdbc:h2:file:./data/neo-copier-db;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE")
                .username("sa")
                .password("")
                .build();
    }

    private String readDatabaseUrlFromDotEnv() {
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
                        if (trimmed.startsWith("DATABASE_URL=")) {
                            String value = trimmed.substring("DATABASE_URL=".length()).trim();
                            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                                value = value.substring(1, value.length() - 1);
                            }
                            log.info("[Database] Loaded DATABASE_URL from {}", file.getCanonicalPath());
                            return value;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String hidePassword(String url) {
        if (url == null) return "";
        return url.replaceAll(":[^/@]+@", ":****@");
    }
}
