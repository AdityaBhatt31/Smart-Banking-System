package com.banking.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class Security {

/**
 * Password storage with PBKDF2-HMAC-SHA256 (salted, slow on purpose).
 * Stored format: "iterations:base64(salt):base64(hash)" so the cost can be raised later.
 */
public static final class PasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String plain) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(plain, salt, ITERATIONS);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verify(String plain, String stored) {
        if (plain == null || stored == null) return false;
        String[] parts = stored.split(":");
        if (parts.length != 3) return false;
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = derive(plain, salt, iterations);
            return MessageDigest.isEqual(expected, actual); // constant-time comparison
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String plain, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(plain.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is not available on this Java version", e);
        }
    }
}

/**
 * Protects personal identifiers (PAN, Aadhaar).
 *
 *  - encrypt / decrypt: AES-256-GCM. Each value gets a random 12-byte IV, and GCM detects tampering.
 *  - keyedHash: HMAC-SHA256 with a key derived from the master key. It lets the bank check
 *    "is this PAN already registered?" without storing the PAN in readable form.
 */
public static final class PiiCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hmacKey;

    public PiiCipher(byte[] masterKey) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalArgumentException("Security key must be 32 bytes (256 bits).");
        }
        this.encryptionKey = new SecretKeySpec(masterKey, "AES");
        this.hmacKey = new SecretKeySpec(derive(masterKey, "hmac-v1"), "HmacSHA256");
    }

    /** Returns IV + ciphertext as one byte array, ready for a VARBINARY column. */
    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(byte[] data) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed: data is corrupt or the security key changed", e);
        }
    }

    /** Lower-case hex of HMAC-SHA256 (64 characters, matches the CHAR(64) columns). */
    public String keyedHash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Hashing failed", e);
        }
    }

    private static byte[] derive(byte[] master, String label) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(master);
            md.update(label.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

/**
 * Verhoeff checksum, the algorithm used for the Aadhaar number check digit.
 * It catches every single-digit error and every adjacent transposition.
 */
public static final class Verhoeff {

    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    private Verhoeff() {}

    /** True if the digit string (including its check digit) is valid. */
    public static boolean isValid(String digits) {
        int c = 0;
        int[] reversed = reverse(digits);
        for (int i = 0; i < reversed.length; i++) {
            c = D[c][P[i % 8][reversed[i]]];
        }
        return c == 0;
    }

    /** The check digit to append to a number that does not yet have one. */
    public static int checkDigit(String digitsWithoutCheck) {
        int c = 0;
        int[] reversed = reverse(digitsWithoutCheck);
        for (int i = 0; i < reversed.length; i++) {
            c = D[c][P[(i + 1) % 8][reversed[i]]];
        }
        return INV[c];
    }

    private static final int[] INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

    private static int[] reverse(String digits) {
        int n = digits.length();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = digits.charAt(n - 1 - i) - '0';
        }
        return out;
    }
}
}
