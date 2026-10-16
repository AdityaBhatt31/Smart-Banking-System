package com.banking.exception;

import java.math.BigDecimal;

/**
 * A business-rule violation (wrong amount, insufficient balance, duplicate PAN, ...).
 * The message is safe to show to the user. The web layer returns it as HTTP 400.
 */
public class BankException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BankException(String message) {
        super(message);
    }

    /** A logged-in user's role is not allowed to perform the requested action. */
    public static final class AccessDeniedException extends BankException {
        private static final long serialVersionUID = 1L;

        public AccessDeniedException(String message) {
            super(message);
        }
    }

    /** A debit would take a customer account below zero. */
    public static final class InsufficientFundsException extends BankException {
        private static final long serialVersionUID = 1L;

        public InsufficientFundsException(BigDecimal available, BigDecimal requested) {
            super("Insufficient balance. Available: Rs " + available.toPlainString()
                    + ", requested: Rs " + requested.toPlainString());
        }
    }
}
