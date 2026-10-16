package com.banking;

import com.banking.service.Services.*;

import java.nio.file.*;

import com.banking.repo.Repositories.*;

import com.banking.config.AppConfig;
import com.banking.db.Database;
import com.banking.security.Security.PiiCipher;
import com.banking.web.ApiServer;


/**
 * Entry point. This is the only place where objects are created and connected (manual dependency injection).
 * Read it top to bottom to see the layers: config -> database -> repositories -> services -> web.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Path configFile = Paths.get("config.properties");
        Path schemaFile = Paths.get("db", "schema.sql");
        Path webRoot = Paths.get("web");
        for (Path required : new Path[]{configFile, schemaFile, webRoot}) {
            if (!Files.exists(required)) {
                throw new IllegalStateException("'" + required + "' not found. Run the program from the project folder.");
            }
        }

        AppConfig cfg = AppConfig.load(configFile);
        if (cfg.get("db.password", "").startsWith("CHANGE_ME")) {
            throw new IllegalStateException("Set your MySQL password in config.properties (db.password) first.");
        }
        int port = args.length > 0 ? Integer.parseInt(args[0]) : cfg.getInt("server.port", 8080);

        // ---- database ----
        Database db = new Database(cfg);
        db.initialize(schemaFile);
        Runtime.getRuntime().addShutdownHook(new Thread(db::close));

        // ---- security ----
        PiiCipher cipher = new PiiCipher(cfg.securityKey());

        // ---- repositories (SQL only) ----
        UserRepository users = new UserRepository();
        CustomerRepository customers = new CustomerRepository(cipher);
        AccountRepository accounts = new AccountRepository();
        LedgerRepository ledgerRepo = new LedgerRepository();
        LendingRepository lending = new LendingRepository();
        AuditRepository audit = new AuditRepository();

        // ---- services (business rules) ----
        UserFactory factory = new UserFactory(users, customers);
        AuthService auth = new AuthService(db, users, audit, factory);
        LedgerService ledger = new LedgerService(accounts, ledgerRepo);
        CustomerService customerService = new CustomerService(db, users, customers, accounts, audit, ledger, cipher);
        PaymentService payments = new PaymentService(db, accounts, ledgerRepo, customers, audit, ledger);
        LendingService lendingService = new LendingService(db, accounts, lending, audit, ledger);
        AdminService admin = new AdminService(db, users, customers, accounts, lending, audit, auth);

        DataSeeder.seedIfEmpty(db, users, audit, customerService);

        // ---- web ----
        ApiServer server = new ApiServer(
                new ApiServer.Services(auth, customerService, payments, lendingService, admin), webRoot);
        server.start(port);

        System.out.println();
        System.out.println("  Smart Banking is running.");
        System.out.println("  Open in browser: http://localhost:" + port);
        System.out.println("  Database:        " + cfg.get("db.name", "smart_bank") + " on " + cfg.get("db.host", "localhost"));
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }
}
