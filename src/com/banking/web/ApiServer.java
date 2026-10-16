package com.banking.web;


import com.banking.repo.Repositories.UserRepository.*;


import com.banking.model.Models.*;

import java.nio.file.*;

import java.io.*;

import com.banking.exception.*;

import java.net.*;

import com.banking.service.Services.*;

import com.sun.net.httpserver.*;

import java.util.*;

import com.banking.db.Database.DataAccessException;
import com.banking.service.Services.CustomerService.*;
import com.banking.service.Services.LedgerService.*;
import com.banking.util.Utilities.Json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * HTTP layer: finds the route, checks the login and role, calls a service, and writes JSON.
 * It contains no business rules. Those live in the service classes.
 */
public class ApiServer {

    /** The services the HTTP layer talks to. */
    public record Services(AuthService auth, CustomerService customers, PaymentService payments,
                           LendingService lending, AdminService admin) {}

    private static final Set<Role> ANY = EnumSet.allOf(Role.class);
    private static final Set<Role> CUSTOMER = EnumSet.of(Role.CUSTOMER);
    private static final Set<Role> STAFF = EnumSet.of(Role.CASHIER, Role.ADMIN);
    private static final Set<Role> CASHIER = EnumSet.of(Role.CASHIER);
    private static final Set<Role> ADMIN = EnumSet.of(Role.ADMIN);

    @FunctionalInterface
    interface Handler {
        Object handle(Ctx c) throws Exception;
    }

    /** roles == null means the route is public. */
    private record Route(String method, String path, Set<Role> roles, Handler handler) {}

    private final Services s;
    private final Path webRoot;
    private final List<Route> routes = new ArrayList<>();
    private HttpServer server;

    public ApiServer(Services services, Path webRoot) {
        this.s = services;
        this.webRoot = webRoot.toAbsolutePath().normalize();
        registerRoutes();
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // =====================================================================
    // Routes
    // =====================================================================

    private void registerRoutes() {
        // ---------- session ----------
        route("POST", "/api/login", null, c -> {
            String token = s.auth().login(c.str("identifier"), c.str("password"));
            User u = s.auth().currentUser(token).orElseThrow(() -> new BankException("Session could not be created."));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", token);
            out.put("user", Views.user(u));
            return out;
        });
        route("POST", "/api/logout", ANY, c -> {
            s.auth().logout(c.user, c.token);
            return ok();
        });
        route("GET", "/api/session", ANY, c -> Views.user(c.user));

        // ---------- customer (self-service) ----------
        route("GET", "/api/customer/overview", CUSTOMER, c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("profile", Views.customer((Customer) c.user));
            out.put("accounts", mapAll(s.payments().myAccounts(c.user), Views::account));
            out.put("transactions", mapAll(s.payments().statement(c.user, 50), Views::ledgerLine));
            out.put("loans", mapAll(s.lending().loansFor(c.user), Views::loan));
            out.put("fixedDeposits", mapAll(s.lending().fixedDepositsFor(c.user), Views::fixedDeposit));
            return out;
        });
        route("POST", "/api/customer/transfer", CUSTOMER, c -> {
            Posting p = s.payments().transfer(c.user, c.str("toAccount"), c.decimal("amount"), c.str("note"));
            return reference(p);
        });
        route("POST", "/api/customer/upi", CUSTOMER, c -> {
            Posting p = s.payments().transfer(c.user, c.str("upiId"), c.decimal("amount"), c.str("note"));
            return reference(p);
        });
        route("GET", "/api/customer/upi/recipient", CUSTOMER, c ->
                Map.of("name", s.payments().recipientName(c.user, c.query("to"))));
        route("POST", "/api/customer/loan", CUSTOMER, c -> {
            LendingService.LoanApplication app = s.lending().applyForLoan(c.user, c.str("type"),
                    c.decimal("amount"), c.integer("tenureMonths"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", app.id());
            out.put("emi", app.emi());
            out.put("annualRate", app.annualRate());
            out.put("status", "PENDING");
            return out;
        });
        route("POST", "/api/customer/loan/repay", CUSTOMER, c -> {
            Posting p = s.lending().repayEmi(c.user, c.longValue("loanId"));
            return reference(p);
        });
        route("POST", "/api/customer/fd", CUSTOMER, c -> {
            LendingService.FdOpened fd = s.lending().openFixedDeposit(c.user, c.decimal("amount"), c.integer("tenureMonths"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", fd.id());
            out.put("annualRate", fd.annualRate());
            out.put("maturityAmount", fd.maturityAmount());
            out.put("maturityDate", fd.maturityDate().toString());
            out.put("reference", fd.txnRef());
            return out;
        });

        // ---------- staff: customer desk ----------
        route("GET", "/api/staff/lookup", STAFF, c -> {
            Customer cu = s.customers().lookup(c.user, c.query("q"));
            List<Account> accounts = s.customers().accountsOf(c.user, cu.getCustomerId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("customer", Views.customer(cu));
            out.put("accounts", mapAll(accounts, Views::account));
            Optional<Account> main = accounts.stream().filter(a -> a instanceof CustomerAccount).findFirst();
            out.put("recent", main.isEmpty() ? List.of()
                    : mapAll(s.payments().statementForStaff(c.user, main.get().getId(), 8), Views::ledgerLine));
            return out;
        });
        route("POST", "/api/staff/customers", STAFF, c -> {
            OnboardRequest r = new OnboardRequest(
                    c.str("fullName"), c.str("dateOfBirth"), c.str("gender"), c.str("mobile"), c.str("email"),
                    c.str("pan"), c.str("aadhaar"), c.str("line1"), c.str("line2"), c.str("city"),
                    c.str("state"), c.str("pincode"), c.str("accountType"), c.str("openingDeposit"),
                    c.str("password"));
            return Views.customer(s.customers().onboard(c.user, r));
        });
        route("POST", "/api/cashier/deposit", CASHIER, c -> reference(
                s.payments().deposit(c.user, c.str("account"), c.decimal("amount"))));
        route("POST", "/api/cashier/withdraw", CASHIER, c -> reference(
                s.payments().withdraw(c.user, c.str("account"), c.decimal("amount"))));

        // ---------- admin ----------
        route("GET", "/api/admin/overview", ADMIN, c -> {
            AdminService.Overview o = s.admin().overview(c.user);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("customers", o.stats().customers());
            stats.put("staff", o.stats().staff());
            stats.put("customerDeposits", o.stats().customerDeposits());
            stats.put("pendingLoans", o.stats().pendingLoans());
            stats.put("trialBalance", o.stats().trialBalance());
            stats.put("ledgerBalanced", o.stats().trialBalance().signum() == 0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stats", stats);
            out.put("users", mapAll(o.users(), Views::userRow));
            out.put("customers", mapAll(o.customers(), Views::customer));
            out.put("loans", mapAll(o.loans(), Views::loan));
            return out;
        });
        route("POST", "/api/admin/kyc", ADMIN, c -> {
            KycStatus status = parseEnum(KycStatus.class, c.str("status"), "KYC status");
            s.customers().setKyc(c.user, c.longValue("customerId"), status);
            return ok();
        });
        route("POST", "/api/admin/users/status", ADMIN, c -> {
            UserStatus status = parseEnum(UserStatus.class, c.str("status"), "user status");
            s.admin().setUserStatus(c.user, c.longValue("userId"), status);
            return ok();
        });
        route("POST", "/api/admin/users/cashier", ADMIN, c -> {
            String username = c.str("username");
            long userId = s.admin().addCashier(c.user, username, c.str("fullName"), c.str("password"));
            Map<String, Object> out = ok();
            out.put("userId", userId);
            out.put("username", username == null ? null : username.trim());
            return out;
        });
        route("POST", "/api/admin/loans/decide", ADMIN, c -> {
            s.lending().decideLoan(c.user, c.longValue("loanId"), c.bool("approve"));
            return ok();
        });
        route("GET", "/api/admin/audit", ADMIN, c -> {
            int limit = c.query("limit") == null ? 100 : Integer.parseInt(c.query("limit"));
            return mapAll(s.admin().auditLog(c.user, limit), Views::audit);
        });
    }

    private void route(String method, String path, Set<Role> roles, Handler handler) {
        routes.add(new Route(method, path, roles, handler));
    }

    // =====================================================================
    // Request handling
    // =====================================================================

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Route route = routes.stream()
                    .filter(r -> r.method().equals(method) && r.path().equals(path))
                    .findFirst().orElse(null);
            if (route == null) {
                sendJson(ex, 404, error("No such API endpoint."));
                return;
            }

            String token = bearerToken(ex);
            Optional<User> user = s.auth().currentUser(token);
            if (route.roles() != null) {
                if (user.isEmpty()) {
                    sendJson(ex, 401, error("Please log in first."));
                    return;
                }
                if (!route.roles().contains(user.get().getRole())) {
                    sendJson(ex, 403, error("You are not allowed to perform this action."));
                    return;
                }
            }
            Ctx ctx = new Ctx(ex, token, user.orElse(null));
            Object result = route.handler().handle(ctx);
            sendJson(ex, 200, result == null ? ok() : result);
        } catch (BankException.AccessDeniedException e) {
            sendJson(ex, 403, error(e.getMessage()));
        } catch (BankException e) {
            sendJson(ex, 400, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, error("Invalid request: " + e.getMessage()));
        } catch (DataAccessException e) {
            if (isDuplicateKey(e)) {
                sendJson(ex, 400, error("A record with the same unique value already exists."));
            } else {
                System.err.println("Database error: " + e.getMessage());
                if (e.getCause() != null) e.getCause().printStackTrace();
                sendJson(ex, 500, error("Database error. Check the server console for details."));
            }
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e);
            e.printStackTrace();
            sendJson(ex, 500, error("Something went wrong on the server."));
        }
    }

    private static boolean isDuplicateKey(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException se && se.getErrorCode() == 1062) return true;
        }
        return false;
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String urlPath = ex.getRequestURI().getPath();
        if (urlPath.equals("/")) urlPath = "/index.html";
        Path file = webRoot.resolve(urlPath.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            byte[] msg = "Not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
            return;
        }
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String bearerToken(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    // ---------- helpers ----------

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static Map<String, Object> reference(Posting p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("reference", p.txnRef());
        return m;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BankException("Invalid " + field + ".");
        }
    }

    private static <T> List<Map<String, Object>> mapAll(Collection<T> items, Function<T, Map<String, Object>> f) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (T item : items) out.add(f.apply(item));
        return out;
    }

    /** Per-request data: the exchange, the logged-in user, and typed readers for the input. */
    static final class Ctx {
        final HttpExchange exchange;
        final String token;
        final User user;
        private Map<String, Object> body;

        Ctx(HttpExchange exchange, String token, User user) {
            this.exchange = exchange;
            this.token = token;
            this.user = user;
        }

        Map<String, Object> body() {
            if (body == null) {
                try (InputStream in = exchange.getRequestBody()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (text.isEmpty()) {
                        body = new LinkedHashMap<>();
                    } else {
                        Object parsed = Json.parse(text);
                        if (!(parsed instanceof Map<?, ?>)) throw new BankException("Request body must be a JSON object.");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) parsed;
                        body = map;
                    }
                } catch (IOException e) {
                    throw new BankException("Could not read the request body.");
                }
            }
            return body;
        }

        String str(String key) {
            Object v = body().get(key);
            return v == null ? null : v.toString();
        }

        boolean bool(String key) {
            return Boolean.TRUE.equals(body().get(key));
        }

        String query(String key) {
            String q = exchange.getRequestURI().getRawQuery();
            if (q == null) return null;
            for (String pair : q.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                if (k.equals(key)) {
                    return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        BigDecimal decimal(String key) {
            BigDecimal v = parseDecimal(body().get(key));
            if (v == null) throw new BankException("Please enter a valid amount.");
            return v;
        }

        int integer(String key) {
            BigDecimal v = parseDecimal(body().get(key));
            try {
                if (v == null) throw new ArithmeticException();
                return v.intValueExact();
            } catch (ArithmeticException e) {
                throw new BankException("'" + key + "' must be a whole number.");
            }
        }

        long longValue(String key) {
            BigDecimal v = parseDecimal(body().get(key));
            try {
                if (v == null) throw new ArithmeticException();
                return v.longValueExact();
            } catch (ArithmeticException e) {
                throw new BankException("'" + key + "' must be a whole number.");
            }
        }

        private static BigDecimal parseDecimal(Object v) {
            try {
                if (v instanceof Number n) return new BigDecimal(n.toString());
                if (v instanceof String s && !s.isBlank()) return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                // treated as missing
            }
            return null;
        }
    }
}

/** Converts domain objects into maps for JSON. Only safe fields are included (never hashes or full PAN). */
final class Views {

    private Views() {}

    static Map<String, Object> user(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("name", u.getFullName());
        m.put("role", u.getRole().name());
        m.put("roleLabel", u.getRole().label());
        m.put("status", u.getStatus().name());
        return m;
    }

    static Map<String, Object> customer(Customer c) {
        Map<String, Object> m = user(c);
        m.put("customerId", c.getCustomerId());
        m.put("cifNo", c.getCifNo());
        m.put("email", c.getEmail());
        m.put("mobile", c.getMobile());
        m.put("dateOfBirth", c.getDateOfBirth().toString());
        m.put("gender", c.getGender());
        m.put("panMasked", c.getPanMasked());
        m.put("aadhaarMasked", "XXXX XXXX " + c.getAadhaarLast4());
        m.put("kycStatus", c.getKycStatus().name());
        if (c.getPermanentAddress() != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("line1", c.getPermanentAddress().getLine1());
            a.put("line2", c.getPermanentAddress().getLine2());
            a.put("city", c.getPermanentAddress().getCity());
            a.put("state", c.getPermanentAddress().getState());
            a.put("pincode", c.getPermanentAddress().getPincode());
            m.put("address", a);
        }
        return m;
    }

    static Map<String, Object> userRow(UserRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("username", r.username());
        m.put("name", r.fullName());
        m.put("role", r.role().name());
        m.put("roleLabel", r.role().label());
        m.put("status", r.status().name());
        m.put("failedAttempts", r.failedAttempts());
        return m;
    }

    static Map<String, Object> account(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("accountNo", a.getAccountNo());
        m.put("type", a instanceof CustomerAccount ca ? ca.kindLabel() : a.kindLabel());
        m.put("balance", a.getBalance());
        m.put("status", a.getStatus().name());
        return m;
    }

    static Map<String, Object> ledgerLine(LedgerLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", l.txnRef());
        m.put("type", l.txnType().name());
        m.put("direction", l.direction().name());
        m.put("amount", l.amount());
        m.put("balanceAfter", l.balanceAfter());
        m.put("narration", l.narration());
        m.put("date", l.createdAt().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")));
        return m;
    }

    static Map<String, Object> loan(Loan l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("customerId", l.getCustomerId());
        m.put("customerName", l.getCustomerName());
        m.put("accountNo", l.getAccountNo());
        m.put("type", l.getLoanType());
        m.put("amount", l.getPrincipal());
        m.put("tenureMonths", l.getTenureMonths());
        m.put("annualRate", l.getAnnualRate());
        m.put("emi", l.getEmi());
        m.put("outstanding", l.getOutstanding());
        m.put("status", l.getStatus().name());
        return m;
    }

    static Map<String, Object> fixedDeposit(FixedDeposit f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("principal", f.getPrincipal());
        m.put("annualRate", f.getAnnualRate());
        m.put("tenureMonths", f.getTenureMonths());
        m.put("maturityAmount", f.getMaturityAmount());
        m.put("maturityDate", f.getMaturityDate().toString());
        m.put("status", f.getStatus());
        return m;
    }

    static Map<String, Object> audit(AuditEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("time", e.createdAt().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss")));
        m.put("user", e.username());
        m.put("action", e.action());
        m.put("entity", e.entity());
        m.put("entityId", e.entityId());
        m.put("details", e.details());
        return m;
    }
}
