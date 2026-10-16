package com.banking.util;


import com.banking.exception.BankException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

public final class Utilities {

/**
 * Tiny JSON reader and writer so the project needs no external libraries.
 * Objects become LinkedHashMap, arrays become List, numbers become BigDecimal, true/false become Boolean.
 */
public static final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    // ---------- Parsing ----------

    public static Object parse(String text) {
        Json p = new Json(text);
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw p.error("Unexpected extra characters");
        }
        return value;
    }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) throw error("Unexpected end of input");
        char c = src.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expectChar(':');
            map.put(key, readValue());
            skipWs();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw error("Expected ',' or '}'");
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            list.add(readValue());
            skipWs();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw error("Expected ',' or ']'");
        }
    }

    private String readString() {
        expectChar('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') break;
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"': case '\\': case '/': sb.append(e); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) throw error("Bad unicode escape");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw error("Bad escape");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private BigDecimal readNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        if (peek() == '0') {
            pos++;
        } else if (peek() >= '1' && peek() <= '9') {
            while (peek() >= '0' && peek() <= '9') pos++;
        } else {
            throw error("Bad number");
        }
        if (peek() == '.') {
            pos++;
            int fractionStart = pos;
            while (peek() >= '0' && peek() <= '9') pos++;
            if (fractionStart == pos) throw error("Bad number");
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') pos++;
            int exponentStart = pos;
            while (peek() >= '0' && peek() <= '9') pos++;
            if (exponentStart == pos) throw error("Bad number");
        }
        try {
            return new BigDecimal(src.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw error("Bad number");
        }
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) throw error("Expected " + word);
        pos += word.length();
    }

    private void expectChar(char ch) {
        skipWs();
        if (next() != ch) throw error("Expected '" + ch + "'");
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= src.length()) throw error("Unexpected end of input");
        return src.charAt(pos++);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("Invalid JSON at position " + pos + ": " + msg);
    }

    // ---------- Writing ----------

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (v instanceof Double d) {
            sb.append(d.isNaN() || d.isInfinite() ? "null" : BigDecimal.valueOf(d).toPlainString());
        } else if (v instanceof Enum<?> e) {
            quote(sb, e.name());
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection<?> col) {
            sb.append('[');
            boolean first = true;
            for (Object item : col) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}

/**
 * Input checks for KYC data, following the Indian formats.
 * Each method throws a BankException with a message the user can understand.
 */
public static final class Validator {

    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    /** Individual PAN: 3 letters, 'P' (4th letter = individual), 1 letter, 4 digits, 1 letter. */
    private static final Pattern PAN = Pattern.compile("^[A-Z]{3}P[A-Z][0-9]{4}[A-Z]$");
    private static final Pattern PINCODE = Pattern.compile("^[1-9][0-9]{5}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,29}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z .'-]{1,119}$");
    private static final Pattern TEXT = Pattern.compile("^[A-Za-z0-9 ,./#()'-]{3,150}$");

    private Validator() {}

    public static String mobile(String v) {
        String s = clean(v);
        if (!MOBILE.matcher(s).matches()) {
            throw new BankException("Mobile number must be 10 digits and start with 6, 7, 8 or 9.");
        }
        return s;
    }

    public static String email(String v) {
        String s = clean(v).toLowerCase();
        if (s.length() > 120 || !EMAIL.matcher(s).matches()) {
            throw new BankException("Please enter a valid email address.");
        }
        return s;
    }

    public static String name(String v, String field) {
        String s = clean(v).replaceAll("\\s+", " ");
        if (!NAME.matcher(s).matches()) {
            throw new BankException(field + " may contain only letters, spaces and . ' - (2 to 120 characters).");
        }
        return s;
    }

    public static String username(String v) {
        String s = clean(v);
        if (!USERNAME.matcher(s).matches()) {
            throw new BankException("Username must be 3 to 30 characters and use only letters, digits, dot, underscore or hyphen.");
        }
        return s;
    }

    public static String pan(String v) {
        String s = clean(v).toUpperCase();
        if (!PAN.matcher(s).matches()) {
            throw new BankException("PAN must look like ABCPE1234F: 10 characters, and the 4th letter must be P for an individual.");
        }
        return s;
    }

    /** Aadhaar: 12 digits, first digit 2-9, and a valid Verhoeff check digit. Spaces are allowed. */
    public static String aadhaar(String v) {
        String s = (v == null ? "" : v).replaceAll("[\\s-]", "");
        if (!s.matches("^[0-9]{12}$")) {
            throw new BankException("Aadhaar number must be exactly 12 digits.");
        }
        return s;
    }

    public static String pincode(String v) {
        String s = clean(v);
        if (!PINCODE.matcher(s).matches()) {
            throw new BankException("Pincode must be 6 digits.");
        }
        return s;
    }

    public static String text(String v, String field, boolean required) {
        String s = clean(v).replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            if (required) throw new BankException(field + " is required.");
            return null;
        }
        if (!TEXT.matcher(s).matches()) {
            throw new BankException(field + " contains characters that are not allowed.");
        }
        return s;
    }

    /** Customers must be at least 18 years old to open an account. */
    public static LocalDate adultDateOfBirth(LocalDate dob) {
        if (dob == null) throw new BankException("Date of birth is required (YYYY-MM-DD).");
        int age = Period.between(dob, LocalDate.now()).getYears();
        if (age < 18) throw new BankException("Customer must be at least 18 years old.");
        if (age > 120) throw new BankException("Please check the date of birth.");
        return dob;
    }

    public static String password(String v) {
        if (v == null || v.length() < 8) {
            throw new BankException("Password must be at least 8 characters.");
        }
        return v;
    }

    private static String clean(String v) {
        return v == null ? "" : v.trim();
    }
}
}
