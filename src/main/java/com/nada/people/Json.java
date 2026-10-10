package com.nada.people;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON (RFC 8259) reader and writer, so the project needs no
 * third-party libraries.
 *
 * <p>Parsed values are: {@link Map} (object), {@link List} (array),
 * {@link String}, {@link Boolean}, {@code null}, and numbers as {@link Long}
 * (integers that fit), {@link BigInteger} (larger integers) or
 * {@link BigDecimal} (anything with a fraction or exponent).
 *
 * <p>Output keeps non-ASCII text as-is (no \\u escaping), so names in any
 * script are returned exactly as supplied.
 */
final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    /** Thrown when text is not valid JSON. */
    static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ parse

    static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected content after JSON value");
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        ParseException error(String message) {
            return new ParseException(message + " at position " + pos);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return readString();
                case 't':
                    readLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    readLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    readLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> readObject(int depth) {
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (consumeIf('}')) {
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw error("expected a string key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                if (consumeIf(',')) {
                    continue;
                }
                expect('}');
                return map;
            }
        }

        private List<Object> readArray(int depth) {
            pos++; // [
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (consumeIf(']')) {
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1));
                skipWhitespace();
                if (consumeIf(',')) {
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c < 0x20) {
                    pos--;
                    throw error("unescaped control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        sb.append(readHex4());
                        break;
                    default:
                        pos--;
                        throw error("invalid escape '\\" + escape + "'");
                }
            }
            String result = sb.toString();
            requireWellFormedUnicode(result);
            return result;
        }

        private char readHex4() {
            if (pos + 4 > text.length()) {
                throw error("incomplete \\u escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(pos + i), 16);
                if (digit < 0) {
                    pos += i;
                    throw error("invalid \\u escape");
                }
                value = value * 16 + digit;
            }
            pos += 4;
            return (char) value;
        }

        /** Rejects unpaired surrogates (e.g. "\ud800"), which cannot be encoded as UTF-8. */
        private void requireWellFormedUnicode(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                } else if (Character.isSurrogate(c)) {
                    throw error("unpaired surrogate in string");
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            consumeIf('-'); // optional sign
            if (atEnd()) {
                throw error("invalid number");
            }
            if (text.charAt(pos) == '0') {
                pos++;
            } else if (isDigit()) {
                skipDigits();
            } else {
                throw error("invalid number");
            }
            boolean integer = true;
            if (consumeIf('.')) {
                integer = false;
                if (!isDigit()) {
                    throw error("invalid number: digits expected after '.'");
                }
                skipDigits();
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                integer = false;
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                if (!isDigit()) {
                    throw error("invalid number: digits expected in exponent");
                }
                skipDigits();
            }
            String literal = text.substring(start, pos);
            if (!integer) {
                return new BigDecimal(literal);
            }
            BigInteger big = new BigInteger(literal);
            return big.bitLength() < Long.SIZE ? (Object) big.longValue() : big;
        }

        private boolean isDigit() {
            return !atEnd() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9';
        }

        private void skipDigits() {
            while (isDigit()) {
                pos++;
            }
        }

        private void readLiteral(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw error("unexpected token");
            }
            pos += literal.length();
        }

        private boolean consumeIf(char expected) {
            if (!atEnd() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consumeIf(expected)) {
                throw error("expected '" + expected + "'");
            }
        }
    }

    // ------------------------------------------------------------------ write

    /** Serializes Map, List, String, Number, Boolean and null values. */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, list.get(i));
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
