package com.toemod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only strict JSON reader producing the shapes the app gets from org.json via JsonMaps: objects
 * as Maps, arrays as Lists, integers as Long, other numbers as Double, plus String, Boolean and null.
 */
final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String s) {
        MiniJson p = new MiniJson(s);
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("trailing data at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String s) {
        return (Map<String, Object>) parse(s);
    }

    private Object value() {
        ws();
        switch (s.charAt(i)) {
            case '{':
                return obj();
            case '[':
                return arr();
            case '"':
                return str();
            case 't':
                return lit("true", Boolean.TRUE);
            case 'f':
                return lit("false", Boolean.FALSE);
            case 'n':
                return lit("null", null);
            default:
                return num();
        }
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        expect('{');
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = str();
            ws();
            expect(':');
            m.put(k, value());
            ws();
            if (s.charAt(i) != ',') break;
            i++;
        }
        expect('}');
        return m;
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        expect('[');
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(value());
            ws();
            if (s.charAt(i) != ',') break;
            i++;
        }
        expect(']');
        return l;
    }

    private String str() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw new IllegalArgumentException("raw control character at " + (i - 1));
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'u':
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                case 'n':
                    b.append('\n');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case '"':
                case '\\':
                case '/':
                    b.append(e);
                    break;
                default:
                    throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
    }

    private Object num() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw new IllegalArgumentException("unexpected '" + s.charAt(i) + "' at " + i);
        if (n.matches("-?\\d+")) {
            try {
                return Long.parseLong(n);
            } catch (NumberFormatException tooBig) {
                return Double.parseDouble(n);
            }
        }
        return Double.parseDouble(n);
    }

    private Object lit(String word, Object v) {
        if (!s.startsWith(word, i)) throw new IllegalArgumentException("bad literal at " + i);
        i += word.length();
        return v;
    }

    private void expect(char c) {
        if (s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i);
        i++;
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }
}
