# Smart Banking 

A core-banking demo with a real MySQL database, customer KYC (mobile, Aadhaar, PAN, address),
a double-entry ledger, maker-checker controls and an audit trail.
Pure Java 17+ (no Tomcat, no Maven) with a plain JavaScript web front end.

> This is a learning and demo project. It is not a licensed banking system. Aadhaar and PAN are checked
> for format and checksum only; there is no live UIDAI, NSDL or CKYC verification.

---

## 1. Requirements

| Software | Version | Notes |
|---|---|---|
| JDK | 17 or newer | `java` and `javac` must be in PATH |
| MySQL Server | 8.0 or newer | Running locally (or reachable on your network) |
| Internet | once | `run.bat` / `run.sh` download the MySQL driver (mysql-connector-j 8.3.0) into `lib/` |

## 2. First-time setup

1. **Start MySQL** and note the root (or other) password.
2. **Create your local configuration** by copying `config.example.properties` to `config.properties`, then set `db.password`.
   The user must be allowed to `CREATE DATABASE` (the root user is). Use another user only if it has that privilege.
3. **Run the app**
   - Windows: double-click `run.bat`
   - Linux / macOS: `./run.sh`
4. The first start:
   - downloads the MySQL driver into `lib/` (only once),
   - compiles the code into `out/`,
   - creates the database `smart_bank` (if missing) and creates all tables from `db/schema.sql`,
   - creates a security key in `config.properties` (`security.key`); keep this local key backed up and private,
   - seeds demo staff and one demo customer.
5. Open **http://localhost:8080** in a browser.

Change the port with `server.port` in `config.properties`, or pass it as an argument (`run.bat 9090`).

### Demo logins

| Role | Login (username, mobile or email) | Password |
|---|---|---|
| Admin | `ADM001` | `admin123` |
| Cashier | `CSH001` | `cash123` |
| Customer | `customer@bank.com` (or mobile `9876543210`) | `customer123` |

## 3. What a cashier does

1. **Open account**: fill in personal details, mobile, email, PAN, Aadhaar, permanent address,
   account type, opening deposit and an initial password. The system creates the customer (CIF number),
   the savings or current account and posts the opening deposit from the cash GL account.
2. **Customer desk**: search by CIF number, mobile number or account number. Post cash deposits and withdrawals.

## 4. What an admin does

- **Dashboard**: customer count, deposits, pending loans, and a ledger health check (trial balance must be zero).
- **Customers & KYC**: verify or reject KYC. Only the admin can verify (maker-checker: the person who
  onboards a customer cannot also verify them).
- **Loan approvals**: approve (disburses the loan into the customer's account) or reject.
- **Users**: disable, enable or unlock staff and customer logins.
- **Cashier accounts**: create cashier logins with a hashed initial password.
- **Audit log**: every login, failed login, onboarding, KYC change, cash posting, transfer, loan decision,
  FD opening and user status change.

## 5. What a customer does

Transfer money to another account by account number, apply for a personal, home or car loan,
open a fixed deposit, and view the statement and profile.

Customer loan repayments post one EMI from the customer's account to the loans ledger account. UPI-style
payments use an internal address in the form `accountnumber@smartbank`; they are ledger transfers inside this
demo and do not connect to India's live UPI network. Monthly due dates and live interest accrual are not modeled.

## 6. Database design

Tables (see `db/schema.sql`):

| Table | Purpose |
|---|---|
| `branches` | Branch master data |
| `users` | Every login: staff and customers. Password stored only as PBKDF2 hash. Tracks failed logins and lock time |
| `customers` | Customer profile: CIF, name, date of birth, gender, mobile, email, KYC status and verifier |
| `addresses` | Permanent and correspondence addresses (one row per type) |
| `accounts` | Customer accounts and internal GL accounts (`kind = CUSTOMER / GL`) |
| `transactions` | One row per business event (deposit, withdrawal, transfer, loan, FD, opening) with a unique reference |
| `ledger_entries` | Double-entry legs: every transaction has DEBIT and CREDIT legs that add up to zero |
| `loans` | Loan applications with EMI and decision status |
| `fixed_deposits` | FD accounts with rate, maturity amount and date |
| `audit_log` | Append-only record of who did what and when |

Rules enforced in the database itself (not only in the Java code):

- `CHECK (kind = 'GL' OR balance >= 0)`: customer accounts can never go negative (no overdraft).
- `CHECK ((kind = 'GL') = (customer_id IS NULL))`: GL accounts have no customer; customer accounts always do.
- `UNIQUE` on mobile, email, PAN hash and Aadhaar hash: one person, one set of identity documents.
- Balances are changed only inside a transaction, with rows locked in ID order (`SELECT ... FOR UPDATE`) to avoid deadlocks.
- Trial balance: the sum of all account balances (customer accounts and GL accounts) must always be zero.
  The admin dashboard checks this.

## 7. KYC and personal data

| Field | Rule | How it is stored |
|---|---|---|
| Mobile | 10 digits, starts with 6-9, unique | Plain text (needed for login and SMS) |
| Email | Valid format, unique | Plain text |
| PAN | Individual format `AAAPA0000A` (4th letter `P`) | Encrypted with AES-256-GCM; a keyed hash (HMAC-SHA256) is used for duplicate checks; the screens show only `XXXXX1234X` |
| Aadhaar | 12 digits, first digit 2-9, Verhoeff checksum | Only the last 4 digits and a keyed hash are stored. The full number is never stored, in line with UIDAI rules; screens show `XXXX XXXX 1234` |
| Address | Line 1, city, state, 6-digit pincode | Plain text |
| Date of birth | Customer must be 18 or older | Date |
| Password | At least 8 characters | PBKDF2-HMAC-SHA256, 120,000 iterations, random salt |

The encryption and hashing key is `security.key` in `config.properties`.
**Back up this key.** If it is lost, encrypted PAN numbers can never be read again, and duplicate checks
will stop working. Do not share it.

## 8. Limits and security rules

| Rule | Value |
|---|---|
| Daily outgoing limit (withdrawals and transfers), KYC verified | Rs 2,00,000 |
| Daily outgoing limit, KYC pending or rejected | Rs 50,000 |
| Maximum per transaction | Rs 10,00,000 |
| Loan eligibility | KYC must be verified; Rs 1,000 to Rs 50,00,000; 6 to 360 months |
| Loan rates (p.a.) | Personal 12.50%, Home 8.50%, Car 9.50% |
| Fixed deposit | Minimum Rs 1,000; 1 to 120 months; 6.50% up to 12 months, 7.00% up to 36 months, 7.50% above |
| Wrong password | 5 wrong passwords lock the login for 15 minutes |
| Idle session | Logged out after 30 minutes of inactivity |
| Disabled user | Existing sessions end immediately |

## 9. Project layout

```
config.properties      settings (database, port, security key)
db/schema.sql         MySQL schema, applied automatically at start
run.bat / run.sh      build and run
lib/                  MySQL driver (downloaded automatically)
web/                  front end: index.html, app.js, style.css
src/com/banking/
  Main.java           start-up; the only place where objects are wired together
  config/             reads config.properties
  db/                 connection pool, transactions, rollback
  security/Security.java   password hashing, PAN encryption, keyed hashes, Verhoeff checksum
  util/Utilities.java      input validation and JSON reader/writer
  model/Models.java        domain types (User, Customer, Account, Loan, FixedDeposit, ...)
  repo/Repositories.java   SQL only (no business rules)
  service/Services.java    business rules and role checks (Auth, Customer, Payment, Lending, Admin, Ledger)
  web/                HTTP API and JSON views (ApiServer)
```

Layers, from the bottom: `repo` (SQL) -> `service` (rules) -> `web` (HTTP). Services never build SQL and
the web layer never applies business rules.

## 10. API (for reference)

| Method | Path | Who |
|---|---|---|
| POST | `/api/login`, `/api/logout` | everyone |
| GET | `/api/session` | logged-in user |
| GET | `/api/customer/overview` | customer |
| POST | `/api/customer/transfer`, `/loan`, `/fd` | customer |
| GET | `/api/staff/lookup?q=` | cashier, admin |
| POST | `/api/staff/customers` (onboarding) | cashier, admin |
| POST | `/api/cashier/deposit`, `/withdraw` | cashier |
| GET | `/api/admin/overview` | admin |
| POST | `/api/admin/kyc`, `/users/status`, `/loans/decide` | admin |
| GET | `/api/admin/audit?limit=` | admin |

Send the token from login as `Authorization: Bearer <token>`.

## 11. Troubleshooting

- **"Cannot connect to MySQL ... Access denied"**: check `db.user` and `db.password` in `config.properties`.
- **"Schema setup failed" or "CREATE command denied"**: the MySQL user cannot create databases. Grant it, or create
  the `smart_bank` database yourself first.
- **"MySQL driver not found"**: the file `lib/mysql-connector-j-8.3.0.jar` is missing. Run the script again with
  internet access, or download the jar from Maven Central and put it in `lib/`.
- **"Set your MySQL password in config.properties"**: you have not replaced the placeholder yet.
- **Port 8080 already in use**: change `server.port` (or run `run.bat 9090`).
- **Changed the security key by mistake**: restore it from your backup. A new key cannot decrypt old PAN data.
- **Reset the demo data**: stop the app, then `DROP DATABASE smart_bank;` in MySQL and start again.
  All data is lost.

## 12. Known limits of this demo

- Single branch, single currency (INR), no OTP or two-factor login, no HTTPS (run on your own machine only).
- No real identity verification: no UIDAI, NSDL or CKYC check, no face match, no document upload.
- No interest posting or statement PDFs; FD maturity is calculated, not paid automatically.
- Loan repayment (EMI debits) is not automated.
