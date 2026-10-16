package com.banking.db;

import java.sql.*;

import java.util.concurrent.*;

import java.util.*;

import java.nio.file.*;

import com.banking.config.AppConfig;

import java.nio.charset.StandardCharsets;

/**
 * Connection handling and transactions.
 *
 *  - {@link #tx(Work)} runs a unit of work in ONE database transaction:
 *    all statements succeed together, or everything is rolled back.
 *  - {@link #read(Work)} runs read-only work without a transaction.
 *  - A small connection pool avoids opening a new connection on every request.
 */
public final class Database {

    /** A piece of database work that receives an open connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final String host;
    private final String port;
    private final String name;
    private final String user;
    private final String password;
    private final String params;
    private final String driver;
    private final BlockingQueue<Connection> pool;

    public Database(AppConfig cfg) {
        this.host = cfg.get("db.host", "localhost");
        this.port = cfg.get("db.port", "3306");
        this.name = cfg.get("db.name", "smart_bank");
        this.user = cfg.get("db.user", "root");
        this.password = cfg.get("db.password", "");
        this.params = cfg.get("db.params", "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata");
        this.driver = cfg.get("db.driver", "com.mysql.cj.jdbc.Driver");
        this.pool = new ArrayBlockingQueue<>(10);
    }

    private String serverUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/?" + params;
    }

    private String databaseUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + name + "?" + params;
    }

    /** Creates the database if needed and applies schema.sql. Safe to call on every start. */
    public void initialize(Path schemaFile) {
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MySQL driver not found. Put mysql-connector-j-*.jar in the 'lib' folder "
                    + "(run.bat / run.sh download it automatically if you have internet).");
        }

        try (Connection c = DriverManager.getConnection(serverUrl(), user, password);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + name
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot connect to MySQL at " + host + ":" + port
                    + " as '" + user + "'. Check db.user / db.password in config.properties. (" + e.getMessage() + ")", e);
        }

        runScript(schemaFile);
        migrateLoanRepaymentType();
    }

    /** Adds the new transaction type for databases created by earlier app versions. */
    private void migrateLoanRepaymentType() {
        String sql = "ALTER TABLE transactions MODIFY txn_type ENUM('ACCOUNT_OPENING','DEPOSIT','WITHDRAWAL',"
                + "'TRANSFER','LOAN_DISBURSAL','LOAN_REPAYMENT','FD_OPENING') NOT NULL";
        try (Connection c = DriverManager.getConnection(databaseUrl(), user, password);
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Loan repayment database migration failed: " + e.getMessage(), e);
        }
    }

    /** Splits the script on ';' (comment lines are removed first) and runs each statement. */
    private void runScript(Path file) {
        List<String> lines = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.trim().startsWith("--")) lines.add(line);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read schema file " + file + ": " + e.getMessage(), e);
        }
        String[] statements = String.join("\n", lines).split(";");
        try (Connection c = DriverManager.getConnection(databaseUrl(), user, password);
             Statement st = c.createStatement()) {
            for (String sql : statements) {
                if (!sql.isBlank()) st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema setup failed: " + e.getMessage(), e);
        }
    }

    /** Runs read-only work. */
    public <T> T read(Work<T> work) {
        Connection c = null;
        try {
            c = borrow();
            return work.run(c);
        } catch (SQLException e) {
            throw new DataAccessException("Database read failed", e);
        } finally {
            giveBack(c);
        }
    }

    /**
     * Runs work inside a single database transaction.
     * A BankException (business rule) or any other error rolls back everything.
     */
    public <T> T tx(Work<T> work) {
        Connection c = null;
        try {
            c = borrow();
            c.setAutoCommit(false);
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new DataAccessException("Database write failed", e);
        } catch (RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        } finally {
            giveBack(c);
        }
    }

    private Connection borrow() throws SQLException {
        Connection c = pool.poll();
        if (c != null) {
            try {
                if (c.isClosed() || !c.isValid(2)) {
                    c.close();
                    c = null;
                }
            } catch (SQLException e) {
                c = null;
            }
        }
        if (c == null) {
            c = DriverManager.getConnection(databaseUrl(), user, password);
        }
        return c;
    }

    private void giveBack(Connection c) {
        if (c == null) return;
        try {
            if (c.isClosed()) return;
            c.setAutoCommit(true);
            if (!pool.offer(c)) c.close();
        } catch (SQLException e) {
            closeQuietly(c);
        }
    }

    private static void rollbackQuietly(Connection c) {
        if (c == null) return;
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the connection is discarded by giveBack when it is in an unknown state
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // nothing else to do
        }
    }

    /** Closes all pooled connections on shutdown. */
    public void close() {
        Connection c;
        while ((c = pool.poll()) != null) closeQuietly(c);
    }


    /** Wraps database errors without exposing SQL details to API clients. */
    public static final class DataAccessException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DataAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
