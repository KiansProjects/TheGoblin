package space.perrys.goblin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler JSON-Parser. Reicht fuer die Antworten von yt-dlp und TMDb.
 * Wenn du das Projekt spaeter auf Jackson umstellst, ist das die einzige
 * Klasse, die du wegwerfen musst.
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    static double num(Map<String, Object> map, String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Double d ? d : fallback;
    }

    private Object value() {
        char c = peek();
        return switch (c) {
            case '{' -> obj();
            case '[' -> arr();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> obj() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            map.put(key, value());
            skipWs();
            char c = src.charAt(pos++);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw err("',' oder '}' erwartet");
            }
        }
    }

    private List<Object> arr() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value());
            skipWs();
            char c = src.charAt(pos++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw err("',' oder ']' erwartet");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw err("unbekannte Escape-Sequenz \\" + e);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw err("Zahl erwartet");
        }
        return Double.valueOf(src.substring(start, pos));
    }

    private Object literal(String word, Object result) {
        if (!src.startsWith(word, pos)) {
            throw err("'" + word + "' erwartet");
        }
        pos += word.length();
        return result;
    }

    private char peek() {
        if (pos >= src.length()) {
            throw err("unerwartetes Ende");
        }
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw err("'" + c + "' erwartet");
        }
        pos++;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private IllegalStateException err(String message) {
        return new IllegalStateException("JSON an Position " + pos + ": " + message);
    }
}
