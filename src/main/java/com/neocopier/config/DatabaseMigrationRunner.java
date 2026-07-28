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
        log.info("[DatabaseMigration] Starting schema migration check...");
        int altered = 0;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            altered += alterColumns(conn, stmt, "accounts", new String[]{
                    "id", "nickname", "role", "mobilenumber", "ucc", "mpin",
                    "consumerkey", "totpsecret", "status", "lastlogin",
                    "accesstoken", "sid", "neotoken", "rid", "hsserverid",
                    "datacenter", "baseurl", "errormessage", "createdat"
            });

            altered += alterColumns(conn, stmt, "orders", new String[]{
                    "id", "masterorderid", "accountid", "accountname", "accountrole",
                    "symbol", "instrument", "optiontype", "expiry", "ordertype",
                    "transactiontype", "status", "errormessage"
            });

            if (altered > 0) {
                log.info("[DatabaseMigration] Completed schema migration. Altered {} column(s) to TEXT.", altered);
            } else {
                log.info("[DatabaseMigration] Schema is up to date. All columns are already TEXT.");
            }
        } catch (Exception e) {
            log.warn("[DatabaseMigration] Migration failed: {}", e.getMessage());
        }
    }

    private int alterColumns(Connection conn, Statement stmt, String table, String[] columns) {
        int count = 0;
        try {
            java.sql.DatabaseMetaData metaData = conn.getMetaData();
            for (String col : columns) {
                if (isAlreadyText(metaData, table, col)) {
                    continue;
                }
                try {
                    stmt.executeUpdate("ALTER TABLE " + table + " ALTER COLUMN " + col + " TYPE TEXT");
                    log.info("[DatabaseMigration] Altered {}.{} -> TEXT", table, col);
                    count++;
                } catch (Exception e) {
                    log.debug("[DatabaseMigration] Skipped {}.{}: {}", table, col, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[DatabaseMigration] Metadata check error for table {}: {}", table, e.getMessage());
        }
        return count;
    }

    private boolean isAlreadyText(java.sql.DatabaseMetaData metaData, String table, String col) {
        try {
            for (String tName : new String[]{table, table.toLowerCase(), table.toUpperCase()}) {
                for (String cName : new String[]{col, col.toLowerCase(), col.toUpperCase()}) {
                    try (java.sql.ResultSet rs = metaData.getColumns(null, null, tName, cName)) {
                        if (rs.next()) {
                            String typeName = rs.getString("TYPE_NAME");
                            if (typeName != null && (typeName.equalsIgnoreCase("text") || typeName.equalsIgnoreCase("clob") || typeName.equalsIgnoreCase("longvarchar"))) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
