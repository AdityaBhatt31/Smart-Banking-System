package com.banking.config;

import java.nio.file.*;

import java.util.*;

import java.io.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Reads config.properties. If the encryption key is missing, a new random one is generated
 * and appended to the file, so the first start works without manual steps.
 */
public final class AppConfig {

    private final Properties props = new Properties();
    private final Path file;

    private AppConfig(Path file) {
        this.file = file;
    }

    public static AppConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Config file not found: " + file.toAbsolutePath()
                    + ". Run the program from the project folder.");
        }
        AppConfig cfg = new AppConfig(file);
        try (InputStream in = Files.newInputStream(file)) {
            cfg.props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        if (cfg.props.getProperty("security.key", "").isBlank()) {
            cfg.generateKey();
        }
        return cfg;
    }

    private void generateKey() throws IOException {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        props.setProperty("security.key", encoded);
        String line = System.lineSeparator() + "# Generated automatically on first start. Keep a backup!"
                + System.lineSeparator() + "security.key=" + encoded + System.lineSeparator();
        try (OutputStream out = Files.newOutputStream(file, java.nio.file.StandardOpenOption.APPEND)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("  Generated a new security key and saved it in " + file.getFileName());
    }

    public String get(String key, String fallback) {
        return props.getProperty(key, fallback).trim();
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public byte[] securityKey() {
        return Base64.getDecoder().decode(props.getProperty("security.key"));
    }
}
