package com.banking.repo;

import com.banking.model.Models.*;
import com.banking.security.Security.PiiCipher;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class Repositories {

/** Data access for accounts, including the row locks used to post money safely. */
public static class AccountRepository {

    /** Snapshot of an account taken while it is locked for update. */
    public record LockedAccount(long id, String kind, BigDecimal balance, AccountStatus status) {}

    /**
     * Locks the rows (SELECT ... FOR UPDATE) in ascending id order.
     * Always locking in the same order prevents deadlocks when two transfers overlap.
     */
    public Map<Long, LockedAccount> lockForUpdate(Connection c, Collection<Long> ids) throws SQLException {
        List<Long> sorted = new ArrayList<>(new java.util.TreeSet<>(ids));
        String marks = String.join(",", java.util.Collections.nCopies(sorted.size(), "?"));
        String sql = "SELECT id, kind, balance, status FROM accounts WHERE id IN (" + marks + ") ORDER BY id FOR UPDATE";
        Map<Long, LockedAccount> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < sorted.size(); i++) ps.setLong(i + 1, sorted.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    out.put(id, new LockedAccount(id, rs.getString("kind"), rs.getBigDecimal("balance"),
                            AccountStatus.valueOf(rs.getString("status"))));
                }
            }
        }
        return out;
    }

    /** Inserts an account with a temporary number. The real number is assigned once the id is known. */
    public long insert(Connection c, Long customerId, int branchId, String kind) throws SQLException {
        String placeholder = "PENDING-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String sql = "INSERT INTO accounts (account_no, customer_id, branch_id, kind, balance) VALUES (?,?,?,?,0.00)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, placeholder);
            if (customerId == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, customerId);
            ps.setInt(3, branchId);
            ps.setString(4, kind);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void assignNumber(Connection c, long id, String accountNo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET account_no = ? WHERE id = ?")) {
            ps.setString(1, accountNo);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void updateBalance(Connection c, long id, BigDecimal balance) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = ? WHERE id = ?")) {
            ps.setBigDecimal(1, balance);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public Optional<Account> findByAccountNo(Connection c, String accountNo) throws SQLException {
        return findOne(c, "account_no = ?", accountNo);
    }

    public Optional<Account> findById(Connection c, long id) throws SQLException {
        return findOne(c, "id = ?", id);
    }

    /** The customer's main account: the earliest savings or current account. */
    public Optional<Account> findPrimaryForCustomer(Connection c, long customerId) throws SQLException {
        String sql = BASE + " WHERE customer_id = ? AND kind IN ('SAVINGS','CURRENT') ORDER BY id LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Looks up the customer's primary account by their registered mobile number. */
    public Optional<Account> findPrimaryForMobile(Connection c, String mobile) throws SQLException {
        String sql = "SELECT a.id, a.account_no, a.customer_id, a.kind, a.balance, a.status "
                + "FROM accounts a JOIN customers cu ON cu.id = a.customer_id "
                + "WHERE cu.mobile = ? AND a.kind IN ('SAVINGS','CURRENT') ORDER BY a.id LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mobile);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Account> listForCustomer(Connection c, long customerId) throws SQLException {
        List<Account> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(BASE + " WHERE customer_id = ? ORDER BY id")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    /** Id of an internal GL account such as GL-CASH. */
    public long glAccountId(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM accounts WHERE account_no = ? AND kind = 'GL'")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Missing internal ledger account " + code);
                return rs.getLong(1);
            }
        }
    }

    /** Sum of every balance. In a correct double-entry ledger this is always 0. */
    public BigDecimal sumAllBalances(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(balance), 0) FROM accounts");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    /** Total money held by customers (excludes internal GL accounts). */
    public BigDecimal sumCustomerBalances(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE kind <> 'GL'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    // ---------------------------------------------------------------

    private static final String BASE =
            "SELECT id, account_no, customer_id, kind, balance, status FROM accounts";

    private Optional<Account> findOne(Connection c, String where, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(BASE + " WHERE " + where)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static Account map(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String no = rs.getString("account_no");
        String kind = rs.getString("kind");
        BigDecimal balance = rs.getBigDecimal("balance");
        AccountStatus status = AccountStatus.valueOf(rs.getString("status"));
        if ("GL".equals(kind)) {
            return new InternalAccount(id, no, balance, status);
        }
        return new CustomerAccount(id, no, rs.getLong("customer_id"), kind, balance, status);
    }
}

/** Append-only audit trail. There are deliberately no update or delete methods. */
public static class AuditRepository {

    public void insert(Connection c, Long userId, String username, String action,
                       String entity, String entityId, String details) throws SQLException {
        String sql = "INSERT INTO audit_log (user_id, username, action, entity, entity_id, details) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (userId == null) ps.setNull(1, java.sql.Types.BIGINT); else ps.setLong(1, userId);
            ps.setString(2, truncate(username, 30));
            ps.setString(3, truncate(action, 60));
            ps.setString(4, truncate(entity, 40));
            ps.setString(5, truncate(entityId, 60));
            ps.setString(6, truncate(details, 500));
            ps.executeUpdate();
        }
    }

    public List<AuditEntry> recent(Connection c, int limit) throws SQLException {
        String sql = "SELECT id, username, action, entity, entity_id, details, created_at "
                + "FROM audit_log ORDER BY id DESC LIMIT ?";
        List<AuditEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AuditEntry(rs.getLong("id"), rs.getString("username"), rs.getString("action"),
                            rs.getString("entity"), rs.getString("entity_id"), rs.getString("details"),
                            rs.getTimestamp("created_at").toLocalDateTime()));
                }
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

/** Data access for customer profiles, KYC and addresses. Decrypts PAN only to mask it. */
public static class CustomerRepository {

    /** Values to store for a new customer. PAN is already encrypted; Aadhaar is already reduced. */
    public record NewCustomer(long userId, String cifPlaceholder, String fullName, LocalDate dateOfBirth,
                              String gender, String mobile, String email, byte[] panEncrypted, String panHash,
                              String aadhaarLast4, String aadhaarHash) {}

    private final PiiCipher cipher;

    public CustomerRepository(PiiCipher cipher) {
        this.cipher = cipher;
    }

    public long insert(Connection c, NewCustomer n) throws SQLException {
        String sql = "INSERT INTO customers (user_id, cif_no, full_name, date_of_birth, gender, mobile, email, "
                + "pan_enc, pan_hash, aadhaar_last4, aadhaar_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, n.userId());
            ps.setString(2, n.cifPlaceholder());
            ps.setString(3, n.fullName());
            ps.setDate(4, java.sql.Date.valueOf(n.dateOfBirth()));
            ps.setString(5, n.gender());
            ps.setString(6, n.mobile());
            ps.setString(7, n.email());
            ps.setBytes(8, n.panEncrypted());
            ps.setString(9, n.panHash());
            ps.setString(10, n.aadhaarLast4());
            ps.setString(11, n.aadhaarHash());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void updateCif(Connection c, long customerId, String cif) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE customers SET cif_no = ? WHERE id = ?")) {
            ps.setString(1, cif);
            ps.setLong(2, customerId);
            ps.executeUpdate();
        }
    }

    public void insertAddress(Connection c, long customerId, String type, Address a) throws SQLException {
        String sql = "INSERT INTO addresses (customer_id, type, line1, line2, city, state, pincode) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            ps.setString(2, type);
            ps.setString(3, a.getLine1());
            ps.setString(4, a.getLine2());
            ps.setString(5, a.getCity());
            ps.setString(6, a.getState());
            ps.setString(7, a.getPincode());
            ps.executeUpdate();
        }
    }

    /**
     * Checks whether mobile, email, PAN or Aadhaar is already registered.
     * Returns the name of the field that clashes, or empty if all four are free.
     */
    public Optional<String> findDuplicate(Connection c, String mobile, String email, String panHash, String aadhaarHash)
            throws SQLException {
        String sql = "SELECT mobile, email, pan_hash, aadhaar_hash FROM customers "
                + "WHERE mobile = ? OR email = ? OR pan_hash = ? OR aadhaar_hash = ? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mobile);
            ps.setString(2, email);
            ps.setString(3, panHash);
            ps.setString(4, aadhaarHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                if (mobile.equals(rs.getString("mobile"))) return Optional.of("mobile number");
                if (email.equals(rs.getString("email"))) return Optional.of("email address");
                if (panHash.equals(rs.getString("pan_hash"))) return Optional.of("PAN");
                return Optional.of("Aadhaar number");
            }
        }
    }

    public Optional<Customer> findByUserId(Connection c, long userId) throws SQLException {
        return findOne(c, "cu.user_id = ?", userId, true);
    }

    /** Looks up by CIF number, mobile number, or any account number that belongs to the customer. */
    public Optional<Customer> findByLookup(Connection c, String key) throws SQLException {
        String sql = BASE + " WHERE cu.cif_no = ? OR cu.mobile = ? "
                + "OR cu.id IN (SELECT customer_id FROM accounts WHERE account_no = ?) LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, key);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs, c, true));
            }
        }
    }

    public List<Customer> listAll(Connection c) throws SQLException {
        List<Customer> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(BASE + " ORDER BY cu.id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs, c, false));
        }
        return out;
    }

    public void setKyc(Connection c, long customerId, KycStatus status, long verifiedByUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE customers SET kyc_status = ?, kyc_verified_by = ?, kyc_verified_at = NOW() WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, verifiedByUserId);
            ps.setLong(3, customerId);
            ps.executeUpdate();
        }
    }

    public Optional<KycStatus> kycStatusOf(Connection c, long customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT kyc_status FROM customers WHERE id = ?")) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(KycStatus.valueOf(rs.getString(1))) : Optional.empty();
            }
        }
    }

    public long countCustomers(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM customers");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public Optional<Long> idForUser(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM customers WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    // ---------------------------------------------------------------

    private static final String BASE =
            "SELECT cu.id AS customer_id, cu.user_id, u.username, u.full_name, u.status, cu.cif_no, cu.email, "
            + "cu.mobile, cu.date_of_birth, cu.gender, cu.pan_enc, cu.aadhaar_last4, cu.kyc_status "
            + "FROM customers cu JOIN users u ON u.id = cu.user_id";

    private Optional<Customer> findOne(Connection c, String where, long param, boolean withAddress) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(BASE + " WHERE " + where)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs, c, withAddress));
            }
        }
    }

    private Customer map(ResultSet rs, Connection c, boolean withAddress) throws SQLException {
        long customerId = rs.getLong("customer_id");
        Address address = withAddress ? permanentAddress(c, customerId) : null;
        return new Customer(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("full_name"),
                UserStatus.valueOf(rs.getString("status")),
                customerId,
                rs.getString("cif_no"),
                rs.getString("email"),
                rs.getString("mobile"),
                rs.getDate("date_of_birth").toLocalDate(),
                rs.getString("gender"),
                maskPan(cipher.decrypt(rs.getBytes("pan_enc"))),
                rs.getString("aadhaar_last4"),
                KycStatus.valueOf(rs.getString("kyc_status")),
                address);
    }

    /** Shows only the characters that banks show on statements, for example XXXXX1234X. */
    static String maskPan(String pan) {
        return "XXXXX" + pan.substring(5, 9) + "X";
    }

    private Address permanentAddress(Connection c, long customerId) throws SQLException {
        String sql = "SELECT line1, line2, city, state, pincode FROM addresses "
                + "WHERE customer_id = ? AND type = 'PERMANENT'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Address(rs.getString("line1"), rs.getString("line2"),
                        rs.getString("city"), rs.getString("state"), rs.getString("pincode"));
            }
        }
    }
}

/** Data access for transactions and their double-entry legs. Rows are only ever inserted, never changed. */
public static class LedgerRepository {

    public long insertTransaction(Connection c, String txnRef, TxType type, BigDecimal amount,
                                  String narration, Channel channel, Long createdBy) throws SQLException {
        String sql = "INSERT INTO transactions (txn_ref, txn_type, amount, narration, channel, created_by) "
                + "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, txnRef);
            ps.setString(2, type.name());
            ps.setBigDecimal(3, amount);
            ps.setString(4, narration);
            ps.setString(5, channel.name());
            if (createdBy == null) ps.setNull(6, java.sql.Types.BIGINT); else ps.setLong(6, createdBy);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void insertLeg(Connection c, long txnId, long accountId, Direction direction,
                          BigDecimal amount, BigDecimal balanceAfter) throws SQLException {
        String sql = "INSERT INTO ledger_entries (txn_id, account_id, direction, amount, balance_after) "
                + "VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, txnId);
            ps.setLong(2, accountId);
            ps.setString(3, direction.name());
            ps.setBigDecimal(4, amount);
            ps.setBigDecimal(5, balanceAfter);
            ps.executeUpdate();
        }
    }

    /**
     * Total of withdrawals and transfers out from this account today.
     * Used to enforce the daily limit. Call it while the account row is locked.
     */
    public BigDecimal todaysOutgoing(Connection c, long accountId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(le.amount), 0) FROM ledger_entries le "
                + "JOIN transactions t ON t.id = le.txn_id "
                + "WHERE le.account_id = ? AND le.direction = 'DEBIT' "
                + "AND t.txn_type IN ('WITHDRAWAL','TRANSFER') AND DATE(t.created_at) = CURDATE()";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    public List<LedgerLine> statement(Connection c, long accountId, int limit) throws SQLException {
        String sql = "SELECT t.txn_ref, t.txn_type, le.direction, le.amount, le.balance_after, t.narration, le.created_at "
                + "FROM ledger_entries le JOIN transactions t ON t.id = le.txn_id "
                + "WHERE le.account_id = ? ORDER BY le.id DESC LIMIT ?";
        List<LedgerLine> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    out.add(new LedgerLine(
                            rs.getString("txn_ref"),
                            TxType.valueOf(rs.getString("txn_type")),
                            Direction.valueOf(rs.getString("direction")),
                            rs.getBigDecimal("amount"),
                            rs.getBigDecimal("balance_after"),
                            rs.getString("narration"),
                            ts.toLocalDateTime()));
                }
            }
        }
        return out;
    }

    /** Returns the database id of a transaction reference (used to link loans and FDs to their postings). */
    public long idOfRef(Connection c, String txnRef) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM transactions WHERE txn_ref = ?")) {
            ps.setString(1, txnRef);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}

/** Data access for loans and fixed deposits. */
public static class LendingRepository {

    private static final String LOAN_SELECT =
            "SELECT l.id, l.customer_id, cu.full_name, a.account_no, l.loan_type, l.principal, l.annual_rate, "
            + "l.tenure_months, l.emi, l.status, GREATEST(l.emi * l.tenure_months - "
            + "COALESCE((SELECT SUM(lp.amount) FROM loan_payments lp WHERE lp.loan_id = l.id), 0), 0) AS outstanding FROM loans l "
            + "JOIN customers cu ON cu.id = l.customer_id JOIN accounts a ON a.id = l.account_id";

    public long insertLoan(Connection c, long customerId, long accountId, String type, BigDecimal principal,
                           BigDecimal rate, int tenureMonths, BigDecimal emi) throws SQLException {
        String sql = "INSERT INTO loans (customer_id, account_id, loan_type, principal, annual_rate, tenure_months, emi) "
                + "VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, customerId);
            ps.setLong(2, accountId);
            ps.setString(3, type);
            ps.setBigDecimal(4, principal);
            ps.setBigDecimal(5, rate);
            ps.setInt(6, tenureMonths);
            ps.setBigDecimal(7, emi);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Locks ONLY the loan row, so two admins cannot decide on the same loan at once.
     * (No JOIN here: a join would also lock customer and account rows and raise deadlock risk.)
     */
    public Optional<Loan> lockLoan(Connection c, long loanId) throws SQLException {
        String sql = "SELECT l.id, l.customer_id, l.loan_type, l.principal, l.annual_rate, l.tenure_months, "
                + "l.emi, l.status, GREATEST(l.emi * l.tenure_months - "
                + "COALESCE((SELECT SUM(lp.amount) FROM loan_payments lp WHERE lp.loan_id = l.id), 0), 0) AS outstanding "
                + "FROM loans l WHERE l.id = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Loan(rs.getLong("id"), rs.getLong("customer_id"), null, null,
                        rs.getString("loan_type"), rs.getBigDecimal("principal"), rs.getBigDecimal("annual_rate"),
                        rs.getInt("tenure_months"), rs.getBigDecimal("emi"),
                        rs.getBigDecimal("outstanding"),
                        Loan.Status.valueOf(rs.getString("status"))));
            }
        }
    }

    public void decideLoan(Connection c, long loanId, Loan.Status status, long decidedByUserId, Long disbursalTxnId)
            throws SQLException {
        String sql = "UPDATE loans SET status = ?, decided_by = ?, decided_at = NOW(), disbursal_txn_id = ? WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, decidedByUserId);
            if (disbursalTxnId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, disbursalTxnId);
            ps.setLong(4, loanId);
            ps.executeUpdate();
        }
    }

    public void recordLoanPayment(Connection c, long loanId, long txnId, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO loan_payments (loan_id, txn_id, amount) VALUES (?,?,?)")) {
            ps.setLong(1, loanId);
            ps.setLong(2, txnId);
            ps.setBigDecimal(3, amount);
            ps.executeUpdate();
        }
    }

    public List<Loan> loansOfCustomer(Connection c, long customerId) throws SQLException {
        return query(c, LOAN_SELECT + " WHERE l.customer_id = ? ORDER BY l.id DESC", customerId);
    }

    public List<Loan> allLoans(Connection c) throws SQLException {
        return query(c, LOAN_SELECT + " ORDER BY l.id DESC", null);
    }

    public long insertFd(Connection c, long customerId, long accountId, BigDecimal principal, BigDecimal rate,
                         int tenureMonths, BigDecimal maturityAmount, LocalDate maturityDate, long openingTxnId)
            throws SQLException {
        String sql = "INSERT INTO fixed_deposits (customer_id, account_id, principal, annual_rate, tenure_months, "
                + "maturity_amount, maturity_date, opening_txn_id) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, customerId);
            ps.setLong(2, accountId);
            ps.setBigDecimal(3, principal);
            ps.setBigDecimal(4, rate);
            ps.setInt(5, tenureMonths);
            ps.setBigDecimal(6, maturityAmount);
            ps.setDate(7, java.sql.Date.valueOf(maturityDate));
            ps.setLong(8, openingTxnId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public List<FixedDeposit> fdsOfCustomer(Connection c, long customerId) throws SQLException {
        String sql = "SELECT id, principal, annual_rate, tenure_months, maturity_amount, maturity_date, status "
                + "FROM fixed_deposits WHERE customer_id = ? ORDER BY id DESC";
        List<FixedDeposit> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FixedDeposit(rs.getLong("id"), rs.getBigDecimal("principal"),
                            rs.getBigDecimal("annual_rate"), rs.getInt("tenure_months"),
                            rs.getBigDecimal("maturity_amount"), rs.getDate("maturity_date").toLocalDate(),
                            rs.getString("status")));
                }
            }
        }
        return out;
    }

    private List<Loan> query(Connection c, String sql, Long param) throws SQLException {
        List<Loan> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    private static Loan map(ResultSet rs) throws SQLException {
        return new Loan(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                rs.getString("full_name"),
                rs.getString("account_no"),
                rs.getString("loan_type"),
                rs.getBigDecimal("principal"),
                rs.getBigDecimal("annual_rate"),
                rs.getInt("tenure_months"),
                rs.getBigDecimal("emi"),
                rs.getBigDecimal("outstanding"),
                Loan.Status.valueOf(rs.getString("status")));
    }
}

/** Data access for the users (login identity) table. */
public static class UserRepository {

    /** Raw login record, including the password hash. Never sent to the browser. */
    public record UserRow(long id, String username, String fullName, String passwordHash, Role role,
                          UserStatus status, int failedAttempts, LocalDateTime lockedUntil) {}

    private static final String COLUMNS =
            "u.id, u.username, u.full_name, u.password_hash, u.role, u.status, u.failed_attempts, u.locked_until";

    /** Finds a user by username, or by the email / mobile of a customer. */
    public Optional<UserRow> findByLogin(Connection c, String identifier) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM users u LEFT JOIN customers cu ON cu.user_id = u.id "
                + "WHERE u.username = ? OR cu.email = ? OR cu.mobile = ? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, identifier);
            ps.setString(2, identifier.toLowerCase());
            ps.setString(3, identifier);
            return firstOrEmpty(ps);
        }
    }

    public Optional<UserRow> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM users u WHERE u.id = ?")) {
            ps.setLong(1, id);
            return firstOrEmpty(ps);
        }
    }

    public List<UserRow> listAll(Connection c) throws SQLException {
        List<UserRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM users u ORDER BY u.id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        }
        return out;
    }

    public long insert(Connection c, String username, String fullName, String passwordHash, Role role) throws SQLException {
        String sql = "INSERT INTO users (username, full_name, password_hash, role) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, fullName);
            ps.setString(3, passwordHash);
            ps.setString(4, role.name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void updateUsername(Connection c, long id, String username) throws SQLException {
        execute(c, "UPDATE users SET username = ? WHERE id = ?", username, id);
    }

    public void recordFailedLogin(Connection c, long id, int attempts, LocalDateTime lockedUntil, UserStatus status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET failed_attempts = ?, locked_until = ?, status = ? WHERE id = ?")) {
            ps.setInt(1, attempts);
            ps.setTimestamp(2, lockedUntil == null ? null : Timestamp.valueOf(lockedUntil));
            ps.setString(3, status.name());
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    public void recordSuccessfulLogin(Connection c, long id) throws SQLException {
        execute(c, "UPDATE users SET failed_attempts = 0, locked_until = NULL, status = 'ACTIVE', "
                + "last_login_at = NOW() WHERE id = ? AND status <> 'DISABLED'", id);
    }

    public void setStatus(Connection c, long id, UserStatus status) throws SQLException {
        execute(c, "UPDATE users SET status = ?, failed_attempts = 0, locked_until = NULL WHERE id = ?",
                status.name(), id);
    }

    private static Optional<UserRow> firstOrEmpty(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
    }

    private static UserRow map(ResultSet rs) throws SQLException {
        Timestamp locked = rs.getTimestamp("locked_until");
        return new UserRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                UserStatus.valueOf(rs.getString("status")),
                rs.getInt("failed_attempts"),
                locked == null ? null : locked.toLocalDateTime());
    }

    private static void execute(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }
}
}
