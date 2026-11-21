
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parses a JSON document. Throws IllegalArgumentException on malformed input. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) {
            throw p.err("Unexpected data after JSON value");
        }
        return v;
    }

    /** Serialises maps, lists, strings, numbers, booleans and null. */
    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        return sb.toString();
    }

    public static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) m;
            return r;
        }
        throw new IllegalArgumentException("Expected a JSON object");
    }

    public static List<Object> asList(Object o) {
        if (o instanceof List<?> l) {
            @SuppressWarnings("unchecked")
            List<Object> r = (List<Object>) l;
            return r;
        }
        throw new IllegalArgumentException("Expected a JSON array");
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return String.valueOf(v);
    }

    // ---------- parsing ----------

    private Object value() {
        if (i >= s.length()) {
            throw err("Unexpected end of JSON");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (!peek('"')) {
                throw err("Expected object key");
            }
            String k = string();
            ws();
            expectChar(':');
            ws();
            m.put(k, value());
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expectChar('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expectChar(']');
            return l;
        }
    }

    private String string() {
        expectChar('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw err("Unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw err("Bad escape");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw err("Bad unicode escape");
                    }
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    throw err("Bad escape");
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw err("Unexpected character");
        }
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException ex) {
            throw err("Bad number");
        }
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expectChar(char c) {
        if (!peek(c)) {
            throw err("Expected '" + c + "'");
        }
        i++;
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw err("Expected " + word);
        }
        i += word.length();
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException(msg + " at position " + i);
    }

   }