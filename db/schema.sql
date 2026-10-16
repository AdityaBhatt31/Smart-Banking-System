CREATE DATABASE IF NOT EXISTS smart_bank CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE smart_bank;

CREATE TABLE IF NOT EXISTS branches (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(20)  NOT NULL UNIQUE,
  ifsc        VARCHAR(11)  NOT NULL UNIQUE,
  name        VARCHAR(100) NOT NULL,
  city        VARCHAR(60)  NOT NULL,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS users (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  username        VARCHAR(30)  NOT NULL UNIQUE,
  full_name       VARCHAR(120) NOT NULL,
  password_hash   VARCHAR(255) NOT NULL,
  role            ENUM('CUSTOMER','CASHIER','ADMIN') NOT NULL,
  status          ENUM('ACTIVE','LOCKED','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  failed_attempts INT          NOT NULL DEFAULT 0,
  locked_until    DATETIME     NULL,
  last_login_at   DATETIME     NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS customers (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT       NOT NULL UNIQUE,
  cif_no          VARCHAR(20)  NOT NULL UNIQUE,
  full_name       VARCHAR(120) NOT NULL,
  date_of_birth   DATE         NOT NULL,
  gender          ENUM('MALE','FEMALE','OTHER') NOT NULL,
  mobile          CHAR(10)     NOT NULL UNIQUE,
  email           VARCHAR(120) NOT NULL UNIQUE,
  pan_enc         VARBINARY(255) NOT NULL,
  pan_hash        CHAR(64)     NOT NULL UNIQUE,
  aadhaar_last4   CHAR(4)      NOT NULL,
  aadhaar_hash    CHAR(64)     NOT NULL UNIQUE,
  kyc_status      ENUM('PENDING','VERIFIED','REJECTED') NOT NULL DEFAULT 'PENDING',
  kyc_verified_by BIGINT       NULL,
  kyc_verified_at DATETIME     NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_customer_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_customer_kyc_by FOREIGN KEY (kyc_verified_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS addresses (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id  BIGINT       NOT NULL,
  type         ENUM('PERMANENT','CORRESPONDENCE') NOT NULL,
  line1        VARCHAR(150) NOT NULL,
  line2        VARCHAR(150) NULL,
  city         VARCHAR(60)  NOT NULL,
  state        VARCHAR(60)  NOT NULL,
  pincode      CHAR(6)      NOT NULL,
  UNIQUE KEY uk_address_type (customer_id, type),
  CONSTRAINT fk_address_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS accounts (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_no   VARCHAR(40)   NOT NULL UNIQUE,
  customer_id  BIGINT        NULL,
  branch_id    INT           NOT NULL,
  kind         ENUM('SAVINGS','CURRENT','GL') NOT NULL,
  balance      DECIMAL(15,2) NOT NULL DEFAULT 0.00,
  status       ENUM('ACTIVE','FROZEN','CLOSED') NOT NULL DEFAULT 'ACTIVE',
  opened_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_no_overdraft CHECK (kind = 'GL' OR balance >= 0),
  CONSTRAINT chk_gl_has_no_customer CHECK ((kind = 'GL') = (customer_id IS NULL)),
  CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
  CONSTRAINT fk_account_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS transactions (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  txn_ref      CHAR(36)      NOT NULL UNIQUE,
  txn_type     ENUM('ACCOUNT_OPENING','DEPOSIT','WITHDRAWAL','TRANSFER','LOAN_DISBURSAL','LOAN_REPAYMENT','FD_OPENING') NOT NULL,
  amount       DECIMAL(15,2) NOT NULL CHECK (amount > 0),
  narration    VARCHAR(200)  NULL,
  channel      ENUM('BRANCH','ONLINE','SYSTEM') NOT NULL,
  created_by   BIGINT        NULL,
  created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_txn_created (created_at),
  CONSTRAINT fk_txn_user FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ledger_entries (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  txn_id        BIGINT        NOT NULL,
  account_id    BIGINT        NOT NULL,
  direction     ENUM('DEBIT','CREDIT') NOT NULL,
  amount        DECIMAL(15,2) NOT NULL CHECK (amount > 0),
  balance_after DECIMAL(15,2) NOT NULL,
  created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_ledger_account (account_id, id),
  CONSTRAINT fk_ledger_txn FOREIGN KEY (txn_id) REFERENCES transactions(id),
  CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES accounts(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS loans (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id       BIGINT        NOT NULL,
  account_id        BIGINT        NOT NULL,
  loan_type         ENUM('PERSONAL','HOME','CAR') NOT NULL,
  principal         DECIMAL(15,2) NOT NULL CHECK (principal > 0),
  annual_rate       DECIMAL(5,2)  NOT NULL,
  tenure_months     INT           NOT NULL,
  emi               DECIMAL(15,2) NOT NULL,
  status            ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  decided_by        BIGINT        NULL,
  decided_at        DATETIME      NULL,
  disbursal_txn_id  BIGINT        NULL,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_loan_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
  CONSTRAINT fk_loan_account FOREIGN KEY (account_id) REFERENCES accounts(id),
  CONSTRAINT fk_loan_decided_by FOREIGN KEY (decided_by) REFERENCES users(id),
  CONSTRAINT fk_loan_txn FOREIGN KEY (disbursal_txn_id) REFERENCES transactions(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS loan_payments (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  loan_id     BIGINT NOT NULL,
  txn_id      BIGINT NOT NULL UNIQUE,
  amount      DECIMAL(15,2) NOT NULL CHECK (amount > 0),
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_loan_payment_loan (loan_id, id),
  CONSTRAINT fk_loan_payment_loan FOREIGN KEY (loan_id) REFERENCES loans(id),
  CONSTRAINT fk_loan_payment_txn FOREIGN KEY (txn_id) REFERENCES transactions(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fixed_deposits (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id     BIGINT        NOT NULL,
  account_id      BIGINT        NOT NULL,
  principal       DECIMAL(15,2) NOT NULL CHECK (principal > 0),
  annual_rate     DECIMAL(5,2)  NOT NULL,
  tenure_months   INT           NOT NULL,
  maturity_amount DECIMAL(15,2) NOT NULL,
  maturity_date   DATE          NOT NULL,
  status          ENUM('ACTIVE','MATURED','CLOSED') NOT NULL DEFAULT 'ACTIVE',
  opening_txn_id  BIGINT        NOT NULL,
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_fd_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
  CONSTRAINT fk_fd_account FOREIGN KEY (account_id) REFERENCES accounts(id),
  CONSTRAINT fk_fd_txn FOREIGN KEY (opening_txn_id) REFERENCES transactions(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS audit_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT       NULL,
  username    VARCHAR(30)  NULL,
  action      VARCHAR(60)  NOT NULL,
  entity      VARCHAR(40)  NULL,
  entity_id   VARCHAR(60)  NULL,
  details     VARCHAR(500) NULL,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_audit_created (created_at)
) ENGINE=InnoDB;

INSERT IGNORE INTO branches (id, code, ifsc, name, city)
VALUES (1, 'SMRT0001', 'SMRT0000001', 'Main Branch', 'Mumbai');

INSERT IGNORE INTO accounts (account_no, customer_id, branch_id, kind, balance)
VALUES ('GL-CASH', NULL, 1, 'GL', 0.00),
       ('GL-LOANS', NULL, 1, 'GL', 0.00),
       ('GL-FD', NULL, 1, 'GL', 0.00);
