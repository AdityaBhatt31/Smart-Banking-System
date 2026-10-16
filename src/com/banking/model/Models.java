package com.banking.model;

import java.math.*;
import java.time.*;

public final class Models {

/**
 * Abstract bank account.
 * Two concrete kinds exist: CustomerAccount (money belongs to a customer) and
 * InternalAccount (general-ledger accounts that track the bank's own side of every movement).
 */
public static abstract class Account {

    private final long id;
    private final String accountNo;
    private final BigDecimal balance;
    private final AccountStatus status;

    protected Account(long id, String accountNo, BigDecimal balance, AccountStatus status) {
        this.id = id;
        this.accountNo = accountNo;
        this.balance = balance;
        this.status = status;
    }

    public long getId() { return id; }
    public String getAccountNo() { return accountNo; }
    public BigDecimal getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }

    /** Polymorphic rule: only internal accounts may go negative. */
    public abstract boolean mayOverdraw();

    public abstract String kindLabel();
}

public enum AccountStatus { ACTIVE, FROZEN, CLOSED }

/** Immutable postal address value object. */
public static final class Address {
    private final String line1;
    private final String line2;
    private final String city;
    private final String state;
    private final String pincode;

    public Address(String line1, String line2, String city, String state, String pincode) {
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.pincode = pincode;
    }

    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getPincode() { return pincode; }
}

/** Head office: manage users, approve loans, view audit log and trial balance. */
public static class Admin extends Staff {
    public Admin(long id, String username, String fullName, UserStatus status) {
        super(id, username, fullName, status);
    }

    @Override
    public Role getRole() {
        return Role.ADMIN;
    }
}

/** One row of the audit trail. */
public record AuditEntry(long id, String username, String action, String entity, String entityId,
                         String details, LocalDateTime createdAt) {}

/** Counter staff: onboard customers, verify KYC, deposit and withdraw cash. */
public static class Cashier extends Staff {
    public Cashier(long id, String username, String fullName, UserStatus status) {
        super(id, username, fullName, status);
    }

    @Override
    public Role getRole() {
        return Role.CASHIER;
    }
}

/** Where a transaction came from. */
public enum Channel { BRANCH, ONLINE, SYSTEM }

/**
 * A bank customer's profile (KYC data). It extends User because a customer also logs in.
 * Sensitive fields are already masked here: the full PAN and Aadhaar never reach this object.
 */
public static class Customer extends User {

    private final long customerId;
    private final String cifNo;
    private final String email;
    private final String mobile;
    private final LocalDate dateOfBirth;
    private final String gender;
    private final String panMasked;
    private final String aadhaarLast4;
    private final KycStatus kycStatus;
    private final Address permanentAddress;

    public Customer(long userId, String username, String fullName, UserStatus status,
                    long customerId, String cifNo, String email, String mobile, LocalDate dateOfBirth,
                    String gender, String panMasked, String aadhaarLast4, KycStatus kycStatus,
                    Address permanentAddress) {
        super(userId, username, fullName, status);
        this.customerId = customerId;
        this.cifNo = cifNo;
        this.email = email;
        this.mobile = mobile;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
        this.panMasked = panMasked;
        this.aadhaarLast4 = aadhaarLast4;
        this.kycStatus = kycStatus;
        this.permanentAddress = permanentAddress;
    }

    public long getCustomerId() { return customerId; }
    public String getCifNo() { return cifNo; }
    public String getEmail() { return email; }
    public String getMobile() { return mobile; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getGender() { return gender; }
    public String getPanMasked() { return panMasked; }
    public String getAadhaarLast4() { return aadhaarLast4; }
    public KycStatus getKycStatus() { return kycStatus; }
    public Address getPermanentAddress() { return permanentAddress; }

    @Override
    public Role getRole() {
        return Role.CUSTOMER;
    }
}

/** A savings or current account owned by a customer. Balance can never go below zero. */
public static class CustomerAccount extends Account {

    private final long customerId;
    private final String kind; // SAVINGS or CURRENT

    public CustomerAccount(long id, String accountNo, long customerId, String kind,
                           BigDecimal balance, AccountStatus status) {
        super(id, accountNo, balance, status);
        this.customerId = customerId;
        this.kind = kind;
    }

    public long getCustomerId() { return customerId; }

    @Override
    public boolean mayOverdraw() {
        return false;
    }

    @Override
    public String kindLabel() {
        return kind.equals("SAVINGS") ? "Savings" : "Current";
    }
}

/**
 * DEBIT reduces an account balance, CREDIT increases it.
 * For every transaction the debits and credits must add up to the same amount.
 */
public enum Direction {
    DEBIT(-1),
    CREDIT(+1);

    private final int sign;

    Direction(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}

/** A fixed deposit. Interest compounds yearly; leftover months earn simple interest. */
public static class FixedDeposit {

    private final long id;
    private final BigDecimal principal;
    private final BigDecimal annualRate;
    private final int tenureMonths;
    private final BigDecimal maturityAmount;
    private final LocalDate maturityDate;
    private final String status;

    public FixedDeposit(long id, BigDecimal principal, BigDecimal annualRate, int tenureMonths,
                        BigDecimal maturityAmount, LocalDate maturityDate, String status) {
        this.id = id;
        this.principal = principal;
        this.annualRate = annualRate;
        this.tenureMonths = tenureMonths;
        this.maturityAmount = maturityAmount;
        this.maturityDate = maturityDate;
        this.status = status;
    }

    public static BigDecimal calculateMaturity(BigDecimal principal, BigDecimal ratePercent, int months) {
        MathContext mc = new MathContext(30);
        BigDecimal growth = BigDecimal.ONE.add(ratePercent.divide(BigDecimal.valueOf(100), mc));
        int years = months / 12;
        int remainingMonths = months % 12;
        BigDecimal value = principal.multiply(growth.pow(years, mc), mc);
        BigDecimal monthlyPart = ratePercent.divide(BigDecimal.valueOf(1200), mc).multiply(BigDecimal.valueOf(remainingMonths));
        value = value.multiply(BigDecimal.ONE.add(monthlyPart), mc);
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public long getId() { return id; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public int getTenureMonths() { return tenureMonths; }
    public BigDecimal getMaturityAmount() { return maturityAmount; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public String getStatus() { return status; }
}

/** General-ledger account (GL-CASH, GL-LOANS, GL-FD). Not owned by any customer. */
public static class InternalAccount extends Account {

    public InternalAccount(long id, String accountNo, BigDecimal balance, AccountStatus status) {
        super(id, accountNo, balance, status);
    }

    @Override
    public boolean mayOverdraw() {
        return true;
    }

    @Override
    public String kindLabel() {
        return "Internal GL";
    }
}

/** Know Your Customer verification state. */
public enum KycStatus { PENDING, VERIFIED, REJECTED }

/** One line of an account statement, seen from that account's point of view. */
public record LedgerLine(String txnRef, TxType txnType, Direction direction, BigDecimal amount,
                         BigDecimal balanceAfter, String narration, LocalDateTime createdAt) {}

/** A loan application. The EMI is calculated once, when the application is created. */
public static class Loan {

    public enum Status { PENDING, APPROVED, REJECTED }

    private final long id;
    private final long customerId;
    private final String customerName;
    private final String loanType;
    private final BigDecimal principal;
    private final BigDecimal annualRate;
    private final int tenureMonths;
    private final BigDecimal emi;
    private final BigDecimal outstanding;
    private final Status status;
    private final String accountNo;

    public Loan(long id, long customerId, String customerName, String accountNo, String loanType,
                BigDecimal principal, BigDecimal annualRate, int tenureMonths, BigDecimal emi,
                BigDecimal outstanding, Status status) {
        this.id = id;
        this.customerId = customerId;
        this.customerName = customerName;
        this.accountNo = accountNo;
        this.loanType = loanType;
        this.principal = principal;
        this.annualRate = annualRate;
        this.tenureMonths = tenureMonths;
        this.emi = emi;
        this.outstanding = outstanding;
        this.status = status;
    }

    /** EMI = P * r * (1+r)^n / ((1+r)^n - 1), where r = annual rate / 12 / 100. */
    public static BigDecimal calculateEmi(BigDecimal principal, BigDecimal annualRatePercent, int months) {
        MathContext mc = new MathContext(30);
        BigDecimal r = annualRatePercent.divide(BigDecimal.valueOf(1200), mc);
        BigDecimal growth = BigDecimal.ONE.add(r).pow(months, mc);
        BigDecimal numerator = principal.multiply(r, mc).multiply(growth, mc);
        BigDecimal denominator = growth.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    public long getId() { return id; }
    public long getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public String getAccountNo() { return accountNo; }
    public String getLoanType() { return loanType; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public int getTenureMonths() { return tenureMonths; }
    public BigDecimal getEmi() { return emi; }
    public BigDecimal getOutstanding() { return outstanding; }
    public Status getStatus() { return status; }
}

/** The three kinds of login users. */
public enum Role {
    CUSTOMER("Customer"),
    CASHIER("Cashier"),
    ADMIN("Administrator");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

/** Branch staff. Shared behaviour for Cashier and Admin lives here (inheritance). */
public static abstract class Staff extends User {
    protected Staff(long id, String username, String fullName, UserStatus status) {
        super(id, username, fullName, status);
    }
}

/** Business event types. The names must match the ENUM values in schema.sql. */
public enum TxType {
    ACCOUNT_OPENING,
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    LOAN_DISBURSAL,
    LOAN_REPAYMENT,
    FD_OPENING
}

/**
 * Abstract base for everyone who can log in.
 * Subclasses: Customer (extends User directly) and Staff (Cashier, Admin).
 */
public static abstract class User {

    private final long id;
    private final String username;
    private final String fullName;
    private final UserStatus status;

    protected User(long id, String username, String fullName, UserStatus status) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.status = status;
    }

    public long getId() { return id; }
    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public UserStatus getStatus() { return status; }

    /** Polymorphic: each subclass knows its own role. */
    public abstract Role getRole();
}

public enum UserStatus { ACTIVE, LOCKED, DISABLED }
}
