package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal, dependency-free SNBT reader/writer.
 * <p>
 * Values are represented with plain Java types so that the parser can be unit tested
 * without Minecraft on the classpath:
 * <ul>
 *     <li>compound  -> {@link LinkedHashMap}&lt;String, Object&gt;</li>
 *     <li>list      -> {@link ArrayList}&lt;Object&gt;</li>
 *     <li>string    -> {@link String}</li>
 *     <li>number / boolean -> {@link Num} (raw literal is kept so it can be written back exactly)</li>
 *     <li>typed array ([I;..], [B;..], [L;..]) -> {@link TypedArray}</li>
 *     <li>SNBT operations like {@code bool(1)} or {@code uuid("...")} -> {@link Raw}</li>
 * </ul>
 * The writer produces SNBT that Minecraft's own {@code TagParser} accepts, which is how
 * the client side turns these values into real item components.
 */
public final class Snbt {

    /** A numeric (or boolean) literal, kept verbatim. */
    public record Num(String raw) {
        public double doubleValue() {
            String s = raw.toLowerCase().replace("_", "");
            if (s.equals("true")) return 1;
            if (s.equals("false")) return 0;
            boolean hex = s.startsWith("0x") || s.startsWith("-0x") || s.startsWith("+0x");
            // strip signedness + type suffixes (1b, 1ub, 1.5f, 3L ...). Hex literals only use
            // integer suffixes preceded by u/s (b and d are hex digits).
            if (hex) {
                if (s.matches(".*[us][bsil]$")) s = s.substring(0, s.length() - 2);
                else if (s.endsWith("l") || s.endsWith("i")) s = s.substring(0, s.length() - 1);
            } else {
                while (!s.isEmpty() && "bsilfdu".indexOf(s.charAt(s.length() - 1)) >= 0) {
                    s = s.substring(0, s.length() - 1);
                }
            }
            if (s.startsWith("+")) s = s.substring(1);
            try {
                if (s.startsWith("0x") || s.startsWith("-0x")) {
                    boolean neg = s.startsWith("-");
                    long v = Long.parseLong(s.substring(neg ? 3 : 2), 16);
                    return neg ? -v : v;
                }
                if (s.startsWith("0b") || s.startsWith("-0b")) {
                    boolean neg = s.startsWith("-");
                    long v = Long.parseLong(s.substring(neg ? 3 : 2), 2);
                    return neg ? -v : v;
                }
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public long longValue() {
            return (long) doubleValue();
        }

        @Override
        public String toString() {
            return raw;
        }
    }

    /** A typed numeric array such as {@code [I;1,2,3]}. */
    public record TypedArray(char type, List<Num> values) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[").append(type).append(';');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(values.get(i).raw());
            }
            return sb.append(']').toString();
        }
    }

    /** Anything we do not interpret but must preserve (e.g. {@code bool(1)}). */
    public record Raw(String raw) {
        @Override
        public String toString() {
            return raw;
        }
    }

    public static final class SnbtException extends RuntimeException {
        public SnbtException(String message, String input, int pos) {
            super(message + " at " + pos + ": ..." + excerpt(input, pos) + "...");
        }

        private static String excerpt(String input, int pos) {
            int from = Math.max(0, pos - 30);
            int to = Math.min(input.length(), pos + 30);
            return input.substring(from, to);
        }
    }

    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|(?:[0-9][0-9_]*\\.?[0-9_]*|\\.[0-9][0-9_]*)(?:[eE][-+]?[0-9_]+)?)(?:[uUsS]?[bBsSiIlL]|[fFdD])?");

    private final String in;
    private int pos;

    private Snbt(String in, int pos) {
        this.in = in;
        this.pos = pos;
    }

    // ------------------------------------------------------------------ public API

    /** Parses a full SNBT value; trailing data is an error. */
    public static Object parse(String input) {
        Snbt p = new Snbt(input, 0);
        Object v = p.readValue();
        p.skipWs();
        if (p.pos != input.length()) throw new SnbtException("Trailing data", input, p.pos);
        return v;
    }

    /**
     * Parses one SNBT value starting at {@code start}. Returns the value and the index just
     * after it, which lets callers parse values embedded in commands.
     */
    public static Parsed parsePrefix(String input, int start) {
        Snbt p = new Snbt(input, start);
        Object v = p.readValue();
        return new Parsed(v, p.pos);
    }

    public record Parsed(Object value, int end) {
    }

    // ------------------------------------------------------------------ reader

    private void skipWs() {
        while (pos < in.length() && Character.isWhitespace(in.charAt(pos))) pos++;
    }

    private char peek() {
        if (pos >= in.length()) throw new SnbtException("Unexpected end of input", in, pos);
        return in.charAt(pos);
    }

    private void expect(char c) {
        skipWs();
        if (pos >= in.length() || in.charAt(pos) != c) throw new SnbtException("Expected '" + c + "'", in, pos);
        pos++;
    }

    private Object readValue() {
        skipWs();
        char c = peek();
        if (c == '{') return readCompound();
        if (c == '[') return readListOrArray();
        if (c == '"' || c == '\'') return readQuoted();
        return readUnquotedValue();
    }

    private Map<String, Object> readCompound() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = readKey();
            expect(':');
            map.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                skipWs();
                if (peek() == '}') { // tolerate trailing comma
                    pos++;
                    return map;
                }
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new SnbtException("Expected ',' or '}'", in, pos);
        }
    }

    private String readKey() {
        skipWs();
        char c = peek();
        if (c == '"' || c == '\'') return readQuoted();
        int start = pos;
        while (pos < in.length() && isUnquotedKeyChar(in.charAt(pos))) pos++;
        if (start == pos) throw new SnbtException("Expected key", in, pos);
        return in.substring(start, pos);
    }

    private static boolean isUnquotedKeyChar(char c) {
        return c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                || c == '_' || c == '-' || c == '.' || c == '+';
    }

    private static boolean isUnquotedValueChar(char c) {
        // a bit more lenient than vanilla so resource locations like minecraft:stone survive
        return isUnquotedKeyChar(c) || c == ':' || c == '/' || c == '#';
    }

    private Object readListOrArray() {
        expect('[');
        skipWs();
        // typed array?
        if (pos + 1 < in.length() && in.charAt(pos + 1) == ';' && "BILbil".indexOf(in.charAt(pos)) >= 0) {
            char type = Character.toUpperCase(in.charAt(pos));
            pos += 2;
            List<Num> values = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return new TypedArray(type, values);
            }
            while (true) {
                Object v = readValue();
                if (!(v instanceof Num n)) throw new SnbtException("Expected number in typed array", in, pos);
                values.add(n);
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return new TypedArray(type, values);
                }
                throw new SnbtException("Expected ',' or ']'", in, pos);
            }
        }
        List<Object> list = new ArrayList<>();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                skipWs();
                if (peek() == ']') {
                    pos++;
                    return list;
                }
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new SnbtException("Expected ',' or ']'", in, pos);
        }
    }

    private String readQuoted() {
        char quote = in.charAt(pos++);
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= in.length()) throw new SnbtException("Unterminated string", in, pos);
            char c = in.charAt(pos++);
            if (c == '\\') {
                if (pos >= in.length()) throw new SnbtException("Unterminated escape", in, pos);
                char e = in.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 's' -> sb.append(' ');
                    case 'x' -> {
                        sb.append((char) Integer.parseInt(in.substring(pos, pos + 2), 16));
                        pos += 2;
                    }
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(in.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e); // \\ \' \" and anything else
                }
            } else if (c == quote) {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
    }

    private Object readUnquotedValue() {
        int start = pos;
        while (pos < in.length() && isUnquotedValueChar(in.charAt(pos))) pos++;
        if (start == pos) throw new SnbtException("Expected value", in, pos);
        String token = in.substring(start, pos);
        // SNBT operation, e.g. bool(1) / uuid("...")
        if (pos < in.length() && in.charAt(pos) == '(') {
            int depth = 0;
            while (pos < in.length()) {
                char c = in.charAt(pos++);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
                else if (c == '"' || c == '\'') {
                    pos--;
                    readQuoted();
                }
            }
            return new Raw(in.substring(start, pos));
        }
        if (token.equalsIgnoreCase("true") || token.equalsIgnoreCase("false")) return new Num(token.toLowerCase());
        if (NUMBER.matcher(token).matches()) return new Num(token);
        return token;
    }

    // ------------------------------------------------------------------ writer

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder sb) {
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeKey(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                write(list.get(i), sb);
            }
            sb.append(']');
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else {
            sb.append(value); // Num, TypedArray, Raw
        }
    }

    private static void writeKey(String key, StringBuilder sb) {
        boolean simple = !key.isEmpty();
        for (int i = 0; i < key.length() && simple; i++) simple = isUnquotedKeyChar(key.charAt(i));
        if (simple) sb.append(key);
        else writeString(key, sb);
    }

    private static void writeString(String s, StringBuilder sb) {
        char quote = s.indexOf('"') >= 0 && s.indexOf('\'') < 0 ? '\'' : '"';
        sb.append(quote);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == quote) sb.append('\\');
            if (c == '\n') {
                sb.append("\\n");
                continue;
            }
            sb.append(c);
        }
        sb.append(quote);
    }

    // ------------------------------------------------------------------ helpers

    /** Deep copy of a parsed value. */
    @SuppressWarnings("unchecked")
    public static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, copy(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(copy(o));
            return out;
        }
        return value; // immutable
    }

    /** Vanilla-style deep merge of {@code source} into {@code target} (compounds only). */
    @SuppressWarnings("unchecked")
    public static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object existing = target.get(e.getKey());
            if (existing instanceof Map<?, ?> em && e.getValue() instanceof Map<?, ?> sm) {
                deepMerge((Map<String, Object>) em, (Map<String, Object>) sm);
            } else {
                target.put(e.getKey(), copy(e.getValue()));
            }
        }
    }

    /**
     * Vanilla "matches" semantics used by path filters: every entry of {@code pattern} must be
     * present in {@code value} (compounds recursively, lists: each pattern element must match
     * some element).
     */
    @SuppressWarnings("unchecked")
    public static boolean matches(Object pattern, Object value) {
        if (pattern instanceof Map<?, ?> pm) {
            if (!(value instanceof Map<?, ?> vm)) return false;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) pm).entrySet()) {
                if (!vm.containsKey(e.getKey()) || !matches(e.getValue(), vm.get(e.getKey()))) return false;
            }
            return true;
        }
        if (pattern instanceof List<?> pl) {
            if (!(value instanceof List<?> vl)) return false;
            for (Object p : pl) {
                boolean found = false;
                for (Object v : vl) {
                    if (matches(p, v)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }
        if (pattern instanceof Num pn && value instanceof Num vn) return pn.doubleValue() == vn.doubleValue();
        return String.valueOf(pattern).equals(String.valueOf(value));
    }

    public static String asString(Object o) {
        return o instanceof String s ? s : o == null ? null : o.toString();
    }

    public static Integer asInt(Object o) {
        return o instanceof Num n ? (int) n.longValue() : null;
    }
}
