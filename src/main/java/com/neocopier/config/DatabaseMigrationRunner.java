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
        log.info("[DatabaseMigration] Starting schema migration...");
        int altered = 0;
        int skipped = 0;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            altered += alterColumns(stmt, "accounts", new String[]{
                    "id", "nickname", "role", "mobilenumber", "ucc", "mpin",
                    "consumerkey", "totpsecret", "status", "lastlogin",
                    "accesstoken", "sid", "neotoken", "rid", "hsserverid",
                    "datacenter", "baseurl", "errormessage", "createdat"
            });

            altered += alterColumns(stmt, "orders", new String[]{
                    "id", "masterorderid", "accountid", "accountname", "accountrole",
                    "symbol", "instrument", "optiontype", "expiry", "ordertype",
                    "transactiontype", "status", "errormessage"
            });

            skipped = 32 - altered; // total columns across both tables
            log.info("[DatabaseMigration] Completed. Altered: {} columns, Already TEXT: {} columns", altered, skipped);
        } catch (Exception e) {
            log.warn("[DatabaseMigration] Migration failed: {}", e.getMessage());
        }
    }

    private int alterColumns(Statement stmt, String table, String[] columns) {
        int count = 0;
        for (String col : columns) {
            try {
                stmt.executeUpdate("ALTER TABLE " + table + " ALTER COLUMN " + col + " TYPE TEXT");
                log.info("[DatabaseMigration] Altered {}.{} -> TEXT", table, col);
                count++;
            } catch (Exception e) {
                // Column already TEXT, table doesn't exist, or SQLite
                log.debug("[DatabaseMigration] Skipped {}.{}: {}", table, col, e.getMessage());
            }
        }
        return count;
    }
}
