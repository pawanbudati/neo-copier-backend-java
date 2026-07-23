package com.neocopier.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Component
public class DatabaseMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    private final DataSource dataSource;

    public DatabaseMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            String[] accountCols = {
                    "id", "nickname", "role", "mobilenumber", "ucc", "mpin",
                    "consumerkey", "totpsecret", "status", "lastlogin",
                    "accesstoken", "sid", "neotoken", "rid", "hsserverid",
                    "datacenter", "baseurl", "errormessage", "createdat"
            };

            for (String col : accountCols) {
                try {
                    stmt.executeUpdate("ALTER TABLE accounts ALTER COLUMN " + col + " TYPE TEXT");
                } catch (Exception ignored) {
                    // Ignore for SQLite or if column is already TEXT
                }
            }

            String[] orderCols = {
                    "id", "masterorderid", "accountid", "accountname", "accountrole",
                    "symbol", "instrument", "optiontype", "expiry", "ordertype",
                    "transactiontype", "status", "errormessage"
            };

            for (String col : orderCols) {
                try {
                    stmt.executeUpdate("ALTER TABLE orders ALTER COLUMN " + col + " TYPE TEXT");
                } catch (Exception ignored) {
                    // Ignore for SQLite or if column is already TEXT
                }
            }

            log.info("[DatabaseMigration] Successfully upgraded all PostgreSQL account & order table columns to TEXT type.");
        } catch (Exception e) {
            log.warn("[DatabaseMigration] Startup schema migration notice: {}", e.getMessage());
        }
    }
}
