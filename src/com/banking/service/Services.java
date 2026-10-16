package com.banking.service;


import com.banking.util.Utilities.*;
import com.banking.db.Database;
import com.banking.exception.*;
import com.banking.model.Models.*;
import com.banking.repo.Repositories.*;
import com.banking.repo.Repositories.AccountRepository.*;
import com.banking.repo.Repositories.CustomerRepository.*;
import com.banking.repo.Repositories.UserRepository.*;
import com.banking.security.Security.*;
import com.banking.service.Services.CustomerService.*;
import com.banking.service.Services.LedgerService.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Services {

/** Role-based access control, checked in the service layer so no route can bypass it. */
public static final class Access {

    private Access() {}

    public static void require(User actor, Role... allowed) {
        if (actor != null) {
            for (Role r : allowed) {
                if (actor.getRole() == r) return;
            }
        }
        throw new BankException.AccessDeniedException("You are not allowed to perform this action.");
    }
}

/** Head-office reporting and user administration. */
public static class AdminService {

    /** Headline numbers for the admin dashboard. */
    public record Stats(long customers, long staff, BigDecimal customerDeposits, long pendingLoans,
                        BigDecimal trialBalance) {}

    public record Overview(Stats stats, List<UserRow> users, List<Customer> customers, List<Loan> loans) {}

    private final Database db;
    private final UserRepository users;
    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final LendingRepository lending;
    private final AuditRepository audit;
    private final AuthService auth;

    public AdminService(Database db, UserRepository users, CustomerRepository customers, AccountRepository accounts,
                        LendingRepository lending, AuditRepository audit, AuthService auth) {
        this.db = db;
        this.users = users;
        this.customers = customers;
        this.accounts = accounts;
        this.lending = lending;
        this.audit = audit;
        this.auth = auth;
    }

    public Overview overview(User actor) {
        Access.require(actor, Role.ADMIN);
        return db.read(c -> {
            List<UserRow> allUsers = users.listAll(c);
            List<Customer> allCustomers = customers.listAll(c);
            List<Loan> allLoans = lending.allLoans(c);
            long staff = allUsers.stream().filter(u -> u.role() != Role.CUSTOMER).count();
            long pending = allLoans.stream().filter(l -> l.getStatus() == Loan.Status.PENDING).count();
            Stats stats = new Stats(
                    allCustomers.size(),
                    staff,
                    accounts.sumCustomerBalances(c),
                    pending,
                    accounts.sumAllBalances(c));   // must be 0 in a correct double-entry ledger
            return new Overview(stats, allUsers, allCustomers, allLoans);
        });
    }

    public void setUserStatus(User actor, long userId, UserStatus status) {
        Access.require(actor, Role.ADMIN);
        if (status != UserStatus.ACTIVE && status != UserStatus.DISABLED) {
            throw new BankException("Status must be ACTIVE or DISABLED.");
        }
        if (userId == actor.getId()) {
            throw new BankException("You cannot change the status of your own account.");
        }
        db.tx(c -> {
            UserRow target = users.findById(c, userId).orElseThrow(() -> new BankException("User not found."));
            users.setStatus(c, userId, status);
            audit.insert(c, actor.getId(), actor.getUsername(),
                    status == UserStatus.DISABLED ? "USER_DISABLED" : "USER_ENABLED", "user", target.username(), null);
            return null;
        });
        if (status == UserStatus.DISABLED) {
            auth.endSessionsOf(userId);
        }
    }

    public long addCashier(User actor, String rawUsername, String rawFullName, String rawPassword) {
        Access.require(actor, Role.ADMIN);
        String username = Validator.username(rawUsername);
        String fullName = Validator.name(rawFullName, "Full name");
        String password = Validator.password(rawPassword);
        return db.tx(c -> {
            long userId = users.insert(c, username, fullName, PasswordHasher.hash(password), Role.CASHIER);
            audit.insert(c, actor.getId(), actor.getUsername(), "CASHIER_CREATED", "user", username,
                    "Cashier login created");
            return userId;
        });
    }

    public List<AuditEntry> auditLog(User actor, int limit) {
        Access.require(actor, Role.ADMIN);
        return db.read(c -> audit.recent(c, Math.min(Math.max(limit, 1), 500)));
    }
}

/**
 * Login, lockout after repeated wrong passwords, and sessions with an inactivity timeout.
 * Every login attempt is written to the audit log, including failed ones.
 */
public static class AuthService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Database db;
    private final UserRepository users;
    private final AuditRepository audit;
    private final UserFactory factory;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(Database db, UserRepository users, AuditRepository audit, UserFactory factory) {
        this.db = db;
        this.users = users;
        this.audit = audit;
        this.factory = factory;
    }

    /** Result of a login attempt. Built inside the transaction, acted on after it commits. */
    private record Outcome(Long userId, String error) {}

    /** A logged-in session. lastSeen moves forward on every request (idle timeout). */
    private static final class Session {
        final long userId;
        volatile Instant lastSeen = Instant.now();

        Session(long userId) {
            this.userId = userId;
        }
    }

    public String login(String identifier, String password) {
        String id = identifier == null ? "" : identifier.trim();
        if (id.isEmpty() || password == null || password.isEmpty()) {
            throw new BankException("Enter your login ID and password.");
        }

        // The failed-attempt counter and the audit row must be committed even when the login is refused,
        // so the transaction returns the outcome instead of throwing.
        Outcome outcome = db.tx(c -> attempt(c, id, password));

        if (outcome.error() != null) {
            throw new BankException(outcome.error());
        }
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(outcome.userId()));
        return token;
    }

    private Outcome attempt(Connection c, String id, String password) throws SQLException {
        Optional<UserRow> found = users.findByLogin(c, id);
        if (found.isEmpty()) {
            audit.insert(c, null, id, "LOGIN_FAILED", "user", null, "unknown login ID");
            return new Outcome(null, "Invalid login ID or password.");
        }
        UserRow u = found.get();
        LocalDateTime now = LocalDateTime.now();

        if (u.status() == UserStatus.DISABLED) {
            audit.insert(c, u.id(), u.username(), "LOGIN_BLOCKED", "user", u.username(), "account disabled");
            return new Outcome(null, "This account is disabled. Please contact the branch.");
        }
        if (u.status() == UserStatus.LOCKED && u.lockedUntil() != null && u.lockedUntil().isAfter(now)) {
            audit.insert(c, u.id(), u.username(), "LOGIN_BLOCKED", "user", u.username(), "account locked");
            return new Outcome(null, "Account is locked after too many failed attempts. Try again after "
                    + u.lockedUntil().format(DateTimeFormatter.ofPattern("HH:mm")) + ".");
        }

        if (!PasswordHasher.verify(password, u.passwordHash())) {
            // If an earlier lock has expired, start counting from zero again.
            boolean lockExpired = u.lockedUntil() != null && !u.lockedUntil().isAfter(now);
            int attempts = (lockExpired ? 0 : u.failedAttempts()) + 1;
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                users.recordFailedLogin(c, u.id(), attempts, now.plus(LOCK_DURATION), UserStatus.LOCKED);
                audit.insert(c, u.id(), u.username(), "ACCOUNT_LOCKED", "user", u.username(),
                        attempts + " failed attempts");
                return new Outcome(null, "Too many failed attempts. The account is locked for 15 minutes.");
            }
            users.recordFailedLogin(c, u.id(), attempts, null, UserStatus.ACTIVE);
            audit.insert(c, u.id(), u.username(), "LOGIN_FAILED", "user", u.username(), "wrong password");
            return new Outcome(null, "Invalid login ID or password.");
        }

        users.recordSuccessfulLogin(c, u.id());
        audit.insert(c, u.id(), u.username(), "LOGIN_SUCCESS", "user", u.username(), null);
        return new Outcome(u.id(), null);
    }

    /** Returns the logged-in user, or empty if the token is unknown, expired, or the account is no longer active. */
    public Optional<User> currentUser(String token) {
        if (token == null) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        if (Duration.between(s.lastSeen, Instant.now()).compareTo(IDLE_TIMEOUT) > 0) {
            sessions.remove(token);
            return Optional.empty();
        }
        Optional<User> user = db.read(c -> factory.load(c, s.userId));
        if (user.isEmpty() || user.get().getStatus() != UserStatus.ACTIVE) {
            sessions.remove(token);
            return Optional.empty();
        }
        s.lastSeen = Instant.now();
        return user;
    }

    public void logout(User user, String token) {
        if (token != null) sessions.remove(token);
        if (user != null) {
            db.tx(c -> {
                audit.insert(c, user.getId(), user.getUsername(), "LOGOUT", "user", user.getUsername(), null);
                return null;
            });
        }
    }

    /** Ends every session of a user, used when an admin disables the account. */
    public void endSessionsOf(long userId) {
        sessions.values().removeIf(s -> s.userId == userId);
    }
}

/**
 * Customer onboarding (KYC), lookup and verification.
 * Onboarding creates the login, the profile, the address, the account and the opening deposit
 * in ONE transaction: either the whole customer exists, or none of it does.
 */
public static class CustomerService {

    /** Everything the counter staff types on the onboarding form. */
    public record OnboardRequest(String fullName, String dateOfBirth, String gender, String mobile, String email,
                                 String pan, String aadhaar, String line1, String line2, String city,
                                 String state, String pincode, String accountType, String openingDeposit,
                                 String password) {}

    /** The seeded main branch (see schema.sql). */
    static final int BRANCH_ID = 1;

    private final Database db;
    private final UserRepository users;
    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final AuditRepository audit;
    private final LedgerService ledger;
    private final PiiCipher cipher;

    public CustomerService(Database db, UserRepository users, CustomerRepository customers,
                           AccountRepository accounts, AuditRepository audit, LedgerService ledger, PiiCipher cipher) {
        this.db = db;
        this.users = users;
        this.customers = customers;
        this.accounts = accounts;
        this.audit = audit;
        this.ledger = ledger;
        this.cipher = cipher;
    }

    public Customer onboard(User actor, OnboardRequest r) {
        Access.require(actor, Role.CASHIER, Role.ADMIN);

        // ---- 1. Validate every field before touching the database ----
        String fullName = Validator.name(r.fullName(), "Full name");
        LocalDate dob = parseDate(r.dateOfBirth());
        Validator.adultDateOfBirth(dob);
        String gender = required(r.gender(), "Gender").toUpperCase();
        if (!gender.equals("MALE") && !gender.equals("FEMALE") && !gender.equals("OTHER")) {
            throw new BankException("Gender must be MALE, FEMALE or OTHER.");
        }
        String mobile = Validator.mobile(r.mobile());
        String email = Validator.email(r.email());
        String pan = Validator.pan(r.pan());
        String aadhaar = Validator.aadhaar(r.aadhaar());
        Address address = new Address(
                Validator.text(r.line1(), "Address line 1", true),
                Validator.text(r.line2(), "Address line 2", false),
                Validator.text(r.city(), "City", true),
                Validator.text(r.state(), "State", true),
                Validator.pincode(r.pincode()));
        String accountType = r.accountType() == null || r.accountType().isBlank() ? "SAVINGS" : r.accountType().toUpperCase();
        if (!accountType.equals("SAVINGS") && !accountType.equals("CURRENT")) {
            throw new BankException("Account type must be SAVINGS or CURRENT.");
        }
        BigDecimal opening = parseAmount(r.openingDeposit(), true);
        String password = Validator.password(r.password());

        // Hashes used for duplicate checks. The plain PAN and Aadhaar are not kept anywhere.
        String panHash = cipher.keyedHash(pan);
        String aadhaarHash = cipher.keyedHash(aadhaar);
        byte[] panEncrypted = cipher.encrypt(pan);
        String aadhaarLast4 = aadhaar.substring(8);

        // ---- 2. Write everything in one transaction ----
        return db.tx(c -> {
            Optional<String> dup = customers.findDuplicate(c, mobile, email, panHash, aadhaarHash);
            if (dup.isPresent()) {
                throw new BankException("This " + dup.get() + " is already registered with another customer.");
            }

            // Username is a unique temporary value until the id is known, then it becomes the CIF number.
            String tempUsername = "NEW" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            long userId = users.insert(c, tempUsername, fullName, PasswordHasher.hash(password), Role.CUSTOMER);
            String cif = "CIF" + String.format("%07d", userId);
            users.updateUsername(c, userId, cif);

            long customerId = customers.insert(c, new NewCustomer(userId, cif, fullName, dob, gender, mobile,
                    email, panEncrypted, panHash, aadhaarLast4, aadhaarHash));
            customers.insertAddress(c, customerId, "PERMANENT", address);

            long accountId = accounts.insert(c, customerId, BRANCH_ID, accountType);
            String accountNo = "1000" + String.format("%08d", accountId);
            accounts.assignNumber(c, accountId, accountNo);

            if (opening.signum() > 0) {
                long cash = accounts.glAccountId(c, "GL-CASH");
                ledger.post(c, TxType.ACCOUNT_OPENING, "Opening deposit for " + accountNo, Channel.BRANCH,
                        actor.getId(), List.of(new Leg(cash, Direction.DEBIT, opening),
                                new Leg(accountId, Direction.CREDIT, opening)));
            }

            audit.insert(c, actor.getId(), actor.getUsername(), "CUSTOMER_ONBOARDED", "customer", cif,
                    accountType + " account " + accountNo + ", opening Rs " + opening.toPlainString());
            return customers.findByUserId(c, userId)
                    .orElseThrow(() -> new IllegalStateException("Customer was not saved"));
        });
    }

    /** Staff search by CIF number, mobile number or account number. */
    public Customer lookup(User actor, String key) {
        Access.require(actor, Role.CASHIER, Role.ADMIN);
        String k = required(key, "Search value").trim();
        return db.read(c -> customers.findByLookup(c, k)
                .orElseThrow(() -> new BankException("No customer found for '" + k + "'.")));
    }

    /**
     * Marks a customer's KYC as verified or rejected.
     * Maker-checker: the cashier who onboards a customer (maker) cannot verify them; only an admin (checker) can.
     */
    public void setKyc(User actor, long customerId, KycStatus status) {
        Access.require(actor, Role.ADMIN);
        if (status == KycStatus.PENDING) {
            throw new BankException("KYC can only be set to VERIFIED or REJECTED.");
        }
        db.tx(c -> {
            KycStatus current = customers.kycStatusOf(c, customerId)
                    .orElseThrow(() -> new BankException("Customer not found."));
            if (current == status) {
                throw new BankException("KYC is already " + status.name().toLowerCase() + ".");
            }
            customers.setKyc(c, customerId, status, actor.getId());
            audit.insert(c, actor.getId(), actor.getUsername(), "KYC_" + status.name(), "customer",
                    String.valueOf(customerId), null);
            return null;
        });
    }

    /** Accounts of a customer, for staff screens. */
    public List<com.banking.model.Models.Account> accountsOf(User actor, long customerId) {
        Access.require(actor, Role.CASHIER, Role.ADMIN);
        return db.read(c -> accounts.listForCustomer(c, customerId));
    }

    // ---------------- helpers ----------------

    private static LocalDate parseDate(String v) {
        try {
            return LocalDate.parse(required(v, "Date of birth").trim());
        } catch (DateTimeParseException e) {
            throw new BankException("Date of birth must be in YYYY-MM-DD format.");
        }
    }

    static BigDecimal parseAmount(String v, boolean allowZero) {
        if (v == null || v.isBlank()) return BigDecimal.ZERO;
        BigDecimal value;
        try {
            value = new BigDecimal(v.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new BankException("Please enter a valid amount.");
        }
        if (value.signum() < 0 || (!allowZero && value.signum() == 0)) {
            throw new BankException("Amount must be greater than zero.");
        }
        return value;
    }

    private static String required(String v, String field) {
        if (v == null || v.isBlank()) throw new BankException(field + " is required.");
        return v.trim();
    }
}

/**
 * Creates demo users on the very first start (when the users table is empty).
 * The demo customer is created through the normal onboarding path, so it has real KYC data.
 */
public static final class DataSeeder {

    private DataSeeder() {}

    public static void seedIfEmpty(Database db, UserRepository users, AuditRepository audit, CustomerService customers) {
        if (!db.read(c -> users.listAll(c).isEmpty())) return;

        long adminId = db.tx(c -> {
            long id = users.insert(c, "ADM001", "Head Office Admin", PasswordHasher.hash("admin123"), Role.ADMIN);
            users.insert(c, "CSH001", "Branch Cashier", PasswordHasher.hash("cash123"), Role.CASHIER);
            audit.insert(c, null, "SYSTEM", "SEED", "database", "initial", "demo users created");
            return id;
        });

        Admin admin = new Admin(adminId, "ADM001", "Head Office Admin", UserStatus.ACTIVE);
        customers.onboard(admin, new OnboardRequest(
                "Demo Customer", "1995-05-10", "MALE", "9876543210", "customer@bank.com",
                "ABCPE1234F", "234567890124", "12 MG Road", null, "Mumbai", "Maharashtra", "400001",
                "SAVINGS", "5000", "customer123"));
        System.out.println("  Demo users created (see README for the login details).");
    }
}

/**
 * The posting engine: the ONLY place where balances change.
 *
 * A posting is a set of legs (account, DEBIT or CREDIT, amount). It is accepted only if:
 *   1. there are at least two legs and the total debits equal the total credits;
 *   2. every account is ACTIVE;
 *   3. no customer account would go below zero.
 * All accounts are locked first, in id order, so concurrent postings cannot corrupt a balance.
 */
public static class LedgerService {

    /** One side of a posting. */
    public record Leg(long accountId, Direction direction, BigDecimal amount) {}

    /** Reference returned to the caller, shown to customers as the transaction reference. */
    public record Posting(long txnId, String txnRef) {}

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public LedgerService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /** Must be called inside a database transaction (Database.tx). */
    public Posting post(Connection c, TxType type, String narration, Channel channel,
                        Long createdBy, List<Leg> legs) throws SQLException {
        BigDecimal total = validate(legs);

        Set<Long> ids = new HashSet<>();
        for (Leg leg : legs) ids.add(leg.accountId());
        Map<Long, LockedAccount> locked = accounts.lockForUpdate(c, ids);
        if (locked.size() != ids.size()) {
            throw new BankException("One of the accounts does not exist.");
        }

        Map<Long, BigDecimal> newBalances = new HashMap<>();
        for (Leg leg : legs) {
            LockedAccount acc = locked.get(leg.accountId());
            if (acc.status() != com.banking.model.Models.AccountStatus.ACTIVE) {
                throw new BankException("Account " + acc.id() + " is frozen or closed and cannot be used.");
            }
            BigDecimal current = acc.balance();
            BigDecimal next = leg.direction() == Direction.CREDIT
                    ? current.add(leg.amount())
                    : current.subtract(leg.amount());
            boolean internal = "GL".equals(acc.kind());
            if (!internal && next.signum() < 0) {
                throw new BankException.InsufficientFundsException(current, leg.amount());
            }
            newBalances.put(leg.accountId(), next);
        }

        String ref = UUID.randomUUID().toString();
        long txnId = ledger.insertTransaction(c, ref, type, total, narration, channel, createdBy);
        for (Leg leg : legs) {
            BigDecimal after = newBalances.get(leg.accountId());
            ledger.insertLeg(c, txnId, leg.accountId(), leg.direction(), leg.amount(), after);
        }
        for (Map.Entry<Long, BigDecimal> e : newBalances.entrySet()) {
            accounts.updateBalance(c, e.getKey(), e.getValue());
        }
        return new Posting(txnId, ref);
    }

    /** Checks the legs and returns the total amount moved. */
    private static BigDecimal validate(List<Leg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two legs.");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        Set<Long> seen = new HashSet<>();
        for (Leg leg : legs) {
            if (leg.amount() == null || leg.amount().signum() <= 0) {
                throw new IllegalArgumentException("Every leg must have a positive amount.");
            }
            if (!seen.add(leg.accountId())) {
                throw new IllegalArgumentException("An account can appear only once in a posting.");
            }
            if (leg.direction() == Direction.DEBIT) debits = debits.add(leg.amount());
            else credits = credits.add(leg.amount());
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalStateException("Unbalanced posting: debits " + debits + " != credits " + credits);
        }
        return debits;
    }

    /** Locks the given accounts (sorted), for callers that must check limits before posting. */
    public Map<Long, LockedAccount> lock(Connection c, List<Long> accountIds) throws SQLException {
        return accounts.lockForUpdate(c, new ArrayList<>(accountIds));
    }
}

/**
 * Loans and fixed deposits.
 *  - Loans need KYC VERIFIED. Approval disburses the money: DEBIT GL-LOANS, CREDIT the customer.
 *  - Fixed deposits move money from the customer's account to GL-FD.
 */
public static class LendingService {

    public static final Map<String, BigDecimal> LOAN_RATES = Map.of(
            "PERSONAL", new BigDecimal("12.50"),
            "HOME", new BigDecimal("8.50"),
            "CAR", new BigDecimal("9.50"));

    static final BigDecimal MIN_LOAN = new BigDecimal("1000.00");
    static final BigDecimal MAX_LOAN = new BigDecimal("5000000.00");
    static final BigDecimal MIN_FD = new BigDecimal("1000.00");

    public record LoanApplication(long id, BigDecimal emi, BigDecimal annualRate) {}
    public record FdOpened(long id, BigDecimal annualRate, BigDecimal maturityAmount, LocalDate maturityDate, String txnRef) {}

    private final Database db;
    private final AccountRepository accounts;
    private final LendingRepository lending;
    private final AuditRepository audit;
    private final LedgerService ledger;

    public LendingService(Database db, AccountRepository accounts, LendingRepository lending,
                          AuditRepository audit, LedgerService ledger) {
        this.db = db;
        this.accounts = accounts;
        this.lending = lending;
        this.audit = audit;
        this.ledger = ledger;
    }

    public LoanApplication applyForLoan(User actor, String loanType, BigDecimal rawAmount, int tenureMonths) {
        Access.require(actor, Role.CUSTOMER);
        Customer me = (Customer) actor;
        if (me.getKycStatus() != KycStatus.VERIFIED) {
            throw new BankException("Loans are available after your KYC is verified at the branch.");
        }
        BigDecimal rate = LOAN_RATES.get(loanType);
        if (rate == null) throw new BankException("Unknown loan type.");
        BigDecimal amount = rawAmount == null ? null : rawAmount.setScale(2, RoundingMode.HALF_UP);
        if (amount == null || amount.compareTo(MIN_LOAN) < 0 || amount.compareTo(MAX_LOAN) > 0) {
            throw new BankException("Loan amount must be between Rs " + MIN_LOAN.toPlainString()
                    + " and Rs " + MAX_LOAN.toPlainString() + ".");
        }
        if (tenureMonths < 6 || tenureMonths > 360) {
            throw new BankException("Tenure must be between 6 and 360 months.");
        }
        BigDecimal emi = Loan.calculateEmi(amount, rate, tenureMonths);

        return db.tx(c -> {
            Account primary = accounts.findPrimaryForCustomer(c, me.getCustomerId())
                    .orElseThrow(() -> new BankException("You do not have an active account."));
            long id = lending.insertLoan(c, me.getCustomerId(), primary.getId(), loanType, amount, rate, tenureMonths, emi);
            audit.insert(c, actor.getId(), actor.getUsername(), "LOAN_APPLIED", "loan", String.valueOf(id),
                    loanType + " Rs " + amount.toPlainString() + " for " + tenureMonths + " months");
            return new LoanApplication(id, emi, rate);
        });
    }

    public void decideLoan(User actor, long loanId, boolean approve) {
        Access.require(actor, Role.ADMIN);
        db.tx(c -> {
            Loan loan = lending.lockLoan(c, loanId).orElseThrow(() -> new BankException("Loan not found."));
            if (loan.getStatus() != Loan.Status.PENDING) {
                throw new BankException("This loan has already been " + loan.getStatus().name().toLowerCase() + ".");
            }
            if (!approve) {
                lending.decideLoan(c, loanId, Loan.Status.REJECTED, actor.getId(), null);
                audit.insert(c, actor.getId(), actor.getUsername(), "LOAN_REJECTED", "loan", String.valueOf(loanId), null);
                return null;
            }
            Account primary = accounts.findPrimaryForCustomer(c, loan.getCustomerId())
                    .orElseThrow(() -> new BankException("The customer has no active account to receive the loan."));
            long loanGl = accounts.glAccountId(c, "GL-LOANS");
            BigDecimal amount = loan.getPrincipal();
            Posting p = ledger.post(c, TxType.LOAN_DISBURSAL, "Loan disbursed: " + loan.getLoanType()
                            + " #" + loanId, Channel.SYSTEM, actor.getId(),
                    List.of(new Leg(loanGl, Direction.DEBIT, amount), new Leg(primary.getId(), Direction.CREDIT, amount)));
            lending.decideLoan(c, loanId, Loan.Status.APPROVED, actor.getId(), p.txnId());
            audit.insert(c, actor.getId(), actor.getUsername(), "LOAN_APPROVED", "loan", String.valueOf(loanId),
                    "Rs " + amount.toPlainString() + " ref " + p.txnRef());
            return null;
        });
    }

    /** Posts one EMI against the approved loan's remaining contracted repayments. */
    public Posting repayEmi(User actor, long loanId) {
        Access.require(actor, Role.CUSTOMER);
        Customer me = (Customer) actor;
        return db.tx(c -> {
            Loan loan = lending.lockLoan(c, loanId).orElseThrow(() -> new BankException("Loan not found."));
            if (loan.getCustomerId() != me.getCustomerId()) {
                throw new BankException("You can repay only your own loan.");
            }
            if (loan.getStatus() != Loan.Status.APPROVED) {
                throw new BankException("Only an approved loan can be repaid.");
            }
            BigDecimal outstanding = loan.getOutstanding();
            if (outstanding.signum() == 0) throw new BankException("This loan is already fully repaid.");
            BigDecimal payment = loan.getEmi().min(outstanding).setScale(2, RoundingMode.HALF_UP);
            Account primary = accounts.findPrimaryForCustomer(c, me.getCustomerId())
                    .orElseThrow(() -> new BankException("You do not have an active account."));
            long loanGl = accounts.glAccountId(c, "GL-LOANS");
            Posting p = ledger.post(c, TxType.LOAN_REPAYMENT, "Loan EMI repayment for loan #" + loanId,
                    Channel.ONLINE, actor.getId(), List.of(new Leg(primary.getId(), Direction.DEBIT, payment),
                            new Leg(loanGl, Direction.CREDIT, payment)));
            lending.recordLoanPayment(c, loanId, p.txnId(), payment);
            audit.insert(c, actor.getId(), actor.getUsername(), "LOAN_REPAYMENT", "loan", String.valueOf(loanId),
                    "Rs " + payment.toPlainString() + " ref " + p.txnRef());
            return p;
        });
    }

    public FdOpened openFixedDeposit(User actor, BigDecimal rawAmount, int tenureMonths) {
        Access.require(actor, Role.CUSTOMER);
        Customer me = (Customer) actor;
        BigDecimal amount = rawAmount == null ? null : rawAmount.setScale(2, RoundingMode.HALF_UP);
        if (amount == null || amount.compareTo(MIN_FD) < 0) {
            throw new BankException("Minimum fixed deposit is Rs " + MIN_FD.toPlainString() + ".");
        }
        if (tenureMonths < 1 || tenureMonths > 120) {
            throw new BankException("FD tenure must be between 1 and 120 months.");
        }
        BigDecimal rate = tenureMonths <= 12 ? new BigDecimal("6.50")
                : tenureMonths <= 36 ? new BigDecimal("7.00") : new BigDecimal("7.50");
        BigDecimal maturity = FixedDeposit.calculateMaturity(amount, rate, tenureMonths);
        LocalDate maturityDate = LocalDate.now().plusMonths(tenureMonths);

        return db.tx(c -> {
            Account primary = accounts.findPrimaryForCustomer(c, me.getCustomerId())
                    .orElseThrow(() -> new BankException("You do not have an active account."));
            long fdGl = accounts.glAccountId(c, "GL-FD");
            Posting p = ledger.post(c, TxType.FD_OPENING, "Fixed deposit for " + tenureMonths + " months", Channel.ONLINE,
                    actor.getId(), List.of(new Leg(primary.getId(), Direction.DEBIT, amount),
                            new Leg(fdGl, Direction.CREDIT, amount)));
            long id = lending.insertFd(c, me.getCustomerId(), primary.getId(), amount, rate, tenureMonths,
                    maturity, maturityDate, p.txnId());
            audit.insert(c, actor.getId(), actor.getUsername(), "FD_OPENED", "fixed_deposit", String.valueOf(id),
                    "Rs " + amount.toPlainString() + " for " + tenureMonths + " months");
            return new FdOpened(id, rate, maturity, maturityDate, p.txnRef());
        });
    }

    public List<Loan> loansFor(User actor) {
        if (actor.getRole() == Role.CUSTOMER) {
            long customerId = ((Customer) actor).getCustomerId();
            return db.read(c -> lending.loansOfCustomer(c, customerId));
        }
        Access.require(actor, Role.ADMIN);
        return db.read(lending::allLoans);
    }

    public List<FixedDeposit> fixedDepositsFor(User actor) {
        Access.require(actor, Role.CUSTOMER);
        long customerId = ((Customer) actor).getCustomerId();
        return db.read(c -> lending.fdsOfCustomer(c, customerId));
    }
}

/**
 * Money movement: cash deposit, cash withdrawal, customer transfer, statements.
 * Business rules enforced here:
 *   - amounts must be positive, rounded to paise, and at most Rs 10,00,000 per transaction;
 *   - daily outgoing limit: Rs 2,00,000 for KYC-verified customers, Rs 50,000 otherwise;
 *   - accounts are locked before the limit is checked, so two parallel requests cannot both pass.
 */
public static class PaymentService {

    static final BigDecimal MAX_PER_TXN = new BigDecimal("1000000.00");
    static final BigDecimal DAILY_LIMIT_VERIFIED = new BigDecimal("200000.00");
    static final BigDecimal DAILY_LIMIT_UNVERIFIED = new BigDecimal("50000.00");

    private final Database db;
    private final AccountRepository accounts;
    private final LedgerRepository ledgerRepo;
    private final CustomerRepository customers;
    private final AuditRepository audit;
    private final LedgerService ledger;

    public PaymentService(Database db, AccountRepository accounts, LedgerRepository ledgerRepo,
                          CustomerRepository customers, AuditRepository audit, LedgerService ledger) {
        this.db = db;
        this.accounts = accounts;
        this.ledgerRepo = ledgerRepo;
        this.customers = customers;
        this.audit = audit;
        this.ledger = ledger;
    }

    public Posting deposit(User actor, String accountNo, BigDecimal rawAmount) {
        Access.require(actor, Role.CASHIER);
        BigDecimal amount = money(rawAmount);
        return db.tx(c -> {
            CustomerAccount acc = customerAccount(c, accountNo);
            long cash = accounts.glAccountId(c, "GL-CASH");
            Posting p = ledger.post(c, TxType.DEPOSIT, "Cash deposit at branch", Channel.BRANCH, actor.getId(),
                    List.of(new Leg(cash, Direction.DEBIT, amount), new Leg(acc.getId(), Direction.CREDIT, amount)));
            audit.insert(c, actor.getId(), actor.getUsername(), "CASH_DEPOSIT", "account", accountNo,
                    "Rs " + amount.toPlainString() + " ref " + p.txnRef());
            return p;
        });
    }

    public Posting withdraw(User actor, String accountNo, BigDecimal rawAmount) {
        Access.require(actor, Role.CASHIER);
        BigDecimal amount = money(rawAmount);
        return db.tx(c -> {
            CustomerAccount acc = customerAccount(c, accountNo);
            ledger.lock(c, List.of(acc.getId()));
            enforceDailyLimit(c, acc, amount);
            long cash = accounts.glAccountId(c, "GL-CASH");
            Posting p = ledger.post(c, TxType.WITHDRAWAL, "Cash withdrawal at branch", Channel.BRANCH, actor.getId(),
                    List.of(new Leg(acc.getId(), Direction.DEBIT, amount), new Leg(cash, Direction.CREDIT, amount)));
            audit.insert(c, actor.getId(), actor.getUsername(), "CASH_WITHDRAWAL", "account", accountNo,
                    "Rs " + amount.toPlainString() + " ref " + p.txnRef());
            return p;
        });
    }

    public Posting transfer(User actor, String toAccountNo, BigDecimal rawAmount, String note) {
        Access.require(actor, Role.CUSTOMER);
        BigDecimal amount = money(rawAmount);
        if (toAccountNo == null || toAccountNo.isBlank()) {
            throw new BankException("Enter the receiver's UPI ID, account number or registered mobile number.");
        }
        Customer me = (Customer) actor;
        String target = toAccountNo.trim();
        boolean upiId = target.toLowerCase(Locale.ROOT).endsWith("@smartbank");
        if (upiId) {
            target = target.substring(0, target.length() - "@smartbank".length());
        }
        final String recipientAccount = target;
        final boolean lookupByMobile = !upiId && recipientAccount.matches("[6-9][0-9]{9}");

        return db.tx(c -> {
            Account from = accounts.findPrimaryForCustomer(c, me.getCustomerId())
                    .orElseThrow(() -> new BankException("You do not have an active account."));
            Optional<Account> recipient = lookupByMobile
                    ? accounts.findPrimaryForMobile(c, recipientAccount)
                    : accounts.findByAccountNo(c, recipientAccount);
            Account to = recipient
                    .filter(a -> a instanceof CustomerAccount)
                    .orElseThrow(() -> new BankException("No account was found for that UPI ID, account number or mobile number."));
            if (from.getId() == to.getId()) {
                throw new BankException("You cannot transfer to your own account.");
            }
            // Lock both accounts together (in id order) before reading any balance.
            ledger.lock(c, List.of(from.getId(), to.getId()));
            enforceDailyLimit(c, from, amount);

            String narration = "Transfer " + from.getAccountNo() + " to " + to.getAccountNo()
                    + (note == null || note.isBlank() ? "" : " - " + note.trim());
            Posting p = ledger.post(c, TxType.TRANSFER, narration, Channel.ONLINE, actor.getId(),
                    List.of(new Leg(from.getId(), Direction.DEBIT, amount), new Leg(to.getId(), Direction.CREDIT, amount)));
            audit.insert(c, actor.getId(), actor.getUsername(), "TRANSFER", "transaction", p.txnRef(),
                    "Rs " + amount.toPlainString() + " to " + to.getAccountNo());
            return p;
        });
    }

    /** Resolves the payee name for the authenticated customer's payment confirmation. */
    public String recipientName(User actor, String identifier) {
        Access.require(actor, Role.CUSTOMER);
        if (identifier == null || identifier.isBlank()) throw new BankException("Enter recipient details.");
        String target = identifier.trim();
        boolean upiId = target.toLowerCase(Locale.ROOT).endsWith("@smartbank");
        if (upiId) target = target.substring(0, target.length() - "@smartbank".length());
        final String key = target;
        return db.read(c -> {
            Optional<Customer> recipient = customers.findByLookup(c, key);
            return recipient.map(Customer::getFullName)
                    .orElseThrow(() -> new BankException("Recipient was not found."));
        });
    }

    /** Statement of the customer's main account, newest first. */
    public List<LedgerLine> statement(User actor, int limit) {
        Access.require(actor, Role.CUSTOMER);
        Customer me = (Customer) actor;
        return db.read(c -> {
            Optional<Account> primary = accounts.findPrimaryForCustomer(c, me.getCustomerId());
            if (primary.isEmpty()) return List.of();
            return ledgerRepo.statement(c, primary.get().getId(), limit);
        });
    }

    /** The logged-in customer's own accounts. */
    public List<Account> myAccounts(User actor) {
        Access.require(actor, Role.CUSTOMER);
        long customerId = ((Customer) actor).getCustomerId();
        return db.read(c -> accounts.listForCustomer(c, customerId));
    }

    /** Customer's accounts as seen by staff. */
    public List<Account> accountsOfCustomer(User actor, long customerId) {
        Access.require(actor, Role.CASHIER, Role.ADMIN);
        return db.read(c -> accounts.listForCustomer(c, customerId));
    }

    /** Statement of any customer account, for staff (counter screen). */
    public List<LedgerLine> statementForStaff(User actor, long accountId, int limit) {
        Access.require(actor, Role.CASHIER, Role.ADMIN);
        return db.read(c -> ledgerRepo.statement(c, accountId, limit));
    }

    // ---------------- rules ----------------

    private CustomerAccount customerAccount(Connection c, String accountNo) throws SQLException {
        if (accountNo == null || accountNo.isBlank()) throw new BankException("Enter the account number.");
        Account a = accounts.findByAccountNo(c, accountNo.trim())
                .orElseThrow(() -> new BankException("Account " + accountNo + " was not found."));
        if (!(a instanceof CustomerAccount ca)) {
            throw new BankException("Cash can only be posted to customer accounts.");
        }
        return ca;
    }

    /** Called while the account is locked. Adds the new amount to today's outgoing total and checks the limit. */
    private void enforceDailyLimit(Connection c, Account acc, BigDecimal amount) throws SQLException {
        KycStatus kyc = kycOf(c, acc);
        BigDecimal limit = kyc == KycStatus.VERIFIED ? DAILY_LIMIT_VERIFIED : DAILY_LIMIT_UNVERIFIED;
        BigDecimal used = ledgerRepo.todaysOutgoing(c, acc.getId());
        if (used.add(amount).compareTo(limit) > 0) {
            throw new BankException("Daily limit of Rs " + limit.toPlainString() + " exceeded. Already used today: Rs "
                    + used.toPlainString() + ". Complete KYC to increase the limit.");
        }
    }

    private KycStatus kycOf(Connection c, Account acc) throws SQLException {
        if (!(acc instanceof CustomerAccount ca)) return KycStatus.PENDING;
        return customers.kycStatusOf(c, ca.getCustomerId()).orElse(KycStatus.PENDING);
    }

    static BigDecimal money(BigDecimal raw) {
        if (raw == null) throw new BankException("Please enter an amount.");
        BigDecimal v = raw.setScale(2, java.math.RoundingMode.HALF_UP);
        if (v.signum() <= 0) throw new BankException("Amount must be greater than zero.");
        if (v.compareTo(MAX_PER_TXN) > 0) {
            throw new BankException("Maximum amount per transaction is Rs " + MAX_PER_TXN.toPlainString() + ".");
        }
        return v;
    }
}

/**
 * Factory: turns a database row into the right User subclass
 * (Customer, Cashier or Admin). Callers never need to know which one they got.
 */
public static class UserFactory {

    private final UserRepository users;
    private final CustomerRepository customers;

    public UserFactory(UserRepository users, CustomerRepository customers) {
        this.users = users;
        this.customers = customers;
    }

    public Optional<User> load(Connection c, long userId) throws SQLException {
        Optional<UserRow> row = users.findById(c, userId);
        if (row.isEmpty()) return Optional.empty();
        UserRow r = row.get();
        switch (r.role()) {
            case CUSTOMER:
                return customers.findByUserId(c, userId).map(cu -> (User) cu);
            case CASHIER:
                return Optional.of(new Cashier(r.id(), r.username(), r.fullName(), r.status()));
            case ADMIN:
                return Optional.of(new Admin(r.id(), r.username(), r.fullName(), r.status()));
            default:
                return Optional.empty();
        }
    }
}
}
