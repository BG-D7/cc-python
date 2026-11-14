package work.diffdev.ccpython;

import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import work.diffdev.ccpython.net.HttpBridge;
import work.diffdev.ccpython.net.SqliteBridge;
import work.diffdev.ccpython.net.WebSocketBridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The only Java object a sandboxed script can reach. Everything goes through the run's Lua driver or through bridges
 * that apply CC's own rules (HTTP address rules, the computer's disk).
 *
 * <p>Values cross the boundary as flat token lists, which need no host map/list access from Python:
 * {@code None/bool/int/float} as themselves, {@code "s", text}, {@code "y", bytes-as-latin1}, {@code "l", n, items...},
 * {@code "d", n, key, value...}, {@code "r", refId, kind, nKeys, keys..., fields} (a Lua function or object kept by
 * the driver; keys name its functions, fields holds its other values or nil).
 */
public final class PyNative {
    private static final int MAX_DEPTH = 64;
    static final String REF_KEY = "__ccpy_ref";

    private final PyRun run;
    private final boolean sandboxed;

    PyNative(PyRun run, boolean sandboxed) {
        this.run = run;
        this.sandboxed = sandboxed;
    }

    // ======================================= Lua access ========================================================

    /** Call a Lua function: target is a dotted path ("turtle.forward") or a ref id, optionally with a field name. */
    @HostAccess.Export
    public Object[] call(Object target, String field, Value args) {
        Object t = target instanceof Number n ? (Object) n.longValue() : bytes(String.valueOf(target));
        List<Object> lua = fromPython(args);
        return toPython(run.request("call", t, field == null ? null : bytes(field), lua, lua.size()));
    }

    /** Read a Lua value by dotted path (functions and objects come back as refs). Result: ok, value. */
    @HostAccess.Export
    public Object[] get(String path) {
        return toPython(run.request("get", bytes(path)));
    }

    /** Run a chunk of Lua code with arguments. Result: ok, values... */
    @HostAccess.Export
    public Object[] lua(String code, Value args) {
        List<Object> lua = fromPython(args);
        return toPython(run.request("lua", bytes(code), lua, lua.size()));
    }

    /** CC's read() (with the REPL's history when asked): ok, line-or-nil; ok=false, "Terminated" on Ctrl+T. */
    @HostAccess.Export
    public Object[] readline(boolean history) {
        return toPython(run.request("read", history, run.takeTypeahead()));
    }

    @HostAccess.Export
    public void write(String text, boolean err) {
        run.write(text, err);
    }

    @HostAccess.Export
    public Object[] pull_event(String filter) {
        Object[] ev = run.pullEvent(filter);
        return toPython(ev);
    }

    /** True if Ctrl+T cut the sleep short. */
    @HostAccess.Export
    public boolean sleep(double seconds) {
        return run.sleep(seconds);
    }

    @HostAccess.Export
    public void queue_event(String name, Value args) {
        run.host.queueEvent(new String(bytes(name), StandardCharsets.ISO_8859_1), fromPython(args).toArray());
    }

    @HostAccess.Export
    public int computer_id() {
        return run.host.id();
    }

    @HostAccess.Export
    public boolean sandboxed() {
        return sandboxed;
    }

    /** Encode text the way it will reach Lua (config charset), as bytes. */
    @HostAccess.Export
    public String to_lua_bytes(String text) {
        return new String(bytes(text), StandardCharsets.ISO_8859_1);
    }

    @HostAccess.Export
    public String from_lua_bytes(String latin1) {
        return TextCodec.decodeLua(latin1, run.config.luaCharset);
    }

    // ======================================= bridges ===========================================================

    @HostAccess.Export
    public Object[] http_request(String method, String url, Value headers, String body, double timeout, boolean redirects) {
        Map<String, String> h = new HashMap<>();
        List<Object> flat = fromPythonStrings(headers);
        for (int i = 0; i + 1 < flat.size(); i += 2) h.put((String) flat.get(i), (String) flat.get(i + 1));
        byte[] data = body == null ? null : body.getBytes(StandardCharsets.ISO_8859_1);
        return HttpBridge.request(run, method, url, h, data, timeout, redirects);
    }

    @HostAccess.Export
    public WebSocketBridge websocket(String url, Value headers, double timeout) {
        Map<String, String> h = new HashMap<>();
        List<Object> flat = fromPythonStrings(headers);
        for (int i = 0; i + 1 < flat.size(); i += 2) h.put((String) flat.get(i), (String) flat.get(i + 1));
        return WebSocketBridge.connect(run, url, h, timeout);
    }

    @HostAccess.Export
    public SqliteBridge sqlite_open(String path) {
        return SqliteBridge.open(run, path);
    }

    // ======================================= conversion =======================================================

    private byte[] bytes(String s) {
        return TextCodec.encode(s, run.config.luaCharset);
    }

    /** Python token list -> Lua-ready Java values (strings become byte[] in the Lua charset). */
    List<Object> fromPython(Value tokens) {
        List<Object> out = new ArrayList<>();
        if (tokens == null || tokens.isNull()) return out;
        int[] pos = { 0 };
        long n = tokens.getArraySize();
        while (pos[0] < n) out.add(readToken(tokens, pos, 0, false));
        return out;
    }

    /** Like {@link #fromPython} but keeps strings as Java strings (for Java-side consumers). */
    private List<Object> fromPythonStrings(Value tokens) {
        List<Object> out = new ArrayList<>();
        if (tokens == null || tokens.isNull()) return out;
        int[] pos = { 0 };
        long n = tokens.getArraySize();
        while (pos[0] < n) out.add(readToken(tokens, pos, 0, true));
        return out;
    }

    private Object readToken(Value t, int[] pos, int depth, boolean javaStrings) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Value is nested too deeply");
        Value v = t.getArrayElement(pos[0]++);
        if (v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.fitsInLong() ? (Object) v.asLong() : (Object) v.asDouble();
        String tag = v.asString();
        switch (tag) {
            case "s" -> {
                String s = t.getArrayElement(pos[0]++).asString();
                return javaStrings ? s : bytes(s);
            }
            case "y" -> {
                String s = t.getArrayElement(pos[0]++).asString();
                return javaStrings ? s : s.getBytes(StandardCharsets.ISO_8859_1);
            }
            case "l" -> {
                int n = t.getArrayElement(pos[0]++).asInt();
                Map<Object, Object> m = new HashMap<>();
                for (int i = 1; i <= n; i++) {
                    Object item = readToken(t, pos, depth + 1, javaStrings);
                    if (item != null) m.put((double) i, item);
                }
                return m;
            }
            case "d" -> {
                int n = t.getArrayElement(pos[0]++).asInt();
                Map<Object, Object> m = new HashMap<>();
                for (int i = 0; i < n; i++) {
                    Object k = readToken(t, pos, depth + 1, javaStrings);
                    Object val = readToken(t, pos, depth + 1, javaStrings);
                    if (k instanceof Long l) k = l.doubleValue();
                    if (k instanceof byte[] b) k = new String(b, StandardCharsets.ISO_8859_1);
                    if (k != null && val != null) m.put(k, val);
                }
                return m;
            }
            case "r" -> {
                long id = t.getArrayElement(pos[0]++).asLong();
                return Map.of(REF_KEY, (double) id);
            }
            default -> throw new IllegalArgumentException("Bad value token " + tag);
        }
    }

    /** Lua values (as CC hands them to Java) -> Python token array. */
    Object[] toPython(Object[] values) {
        List<Object> out = new ArrayList<>();
        if (values != null) for (Object v : values) writeToken(v, out, 0);
        return out.toArray();
    }

    Object[] toPython(Object value) {
        List<Object> out = new ArrayList<>();
        writeToken(value, out, 0);
        return out.toArray();
    }

    private void writeToken(Object v, List<Object> out, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Lua table is nested too deeply (or recursive)");
        if (v == null || v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            out.add(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) out.add((long) d);
            else out.add(d);
        } else if (v instanceof String s) {
            out.add("s");
            out.add(TextCodec.decodeLua(s, run.config.luaCharset));
        } else if (v instanceof byte[] b) {
            out.add("s");
            out.add(TextCodec.decode(b, run.config.luaCharset));
        } else if (v instanceof Map<?, ?> m) {
            Object ref = m.get(REF_KEY);
            if (ref instanceof Number id) {
                Object kind = m.get("kind");
                Object keys = m.get("keys");
                List<String> names = new ArrayList<>();
                if (keys instanceof Map<?, ?> km) {
                    for (Object k : km.values()) if (k instanceof String ks) names.add(TextCodec.decodeLua(ks, run.config.luaCharset));
                }
                out.add("r");
                out.add(id.longValue());
                out.add("table".equals(kind) ? "t" : "f");
                out.add(names.size());
                out.addAll(names);
                writeToken(m.get("fields"), out, depth + 1);
                return;
            }
            int n = sequenceLength(m);
            if (n >= 0) {
                out.add("l");
                out.add(n);
                for (int i = 1; i <= n; i++) writeToken(m.get((double) i), out, depth + 1);
            } else {
                out.add("d");
                out.add(m.size());
                for (var e : m.entrySet()) {
                    writeToken(e.getKey(), out, depth + 1);
                    writeToken(e.getValue(), out, depth + 1);
                }
            }
        } else {
            out.add("s");
            out.add(String.valueOf(v));
        }
    }

    /** n if the table is exactly {1..n} (n may be 0 for an empty table), else -1. */
    private static int sequenceLength(Map<?, ?> m) {
        int n = m.size();
        for (int i = 1; i <= n; i++) if (!m.containsKey((double) i)) return -1;
        return n;
    }

    /** Convert a Python token list (for python.eval results) into a Lua value. */
    Object toLua(Value tokens) {
        List<Object> v = fromPython(tokens);
        return v.isEmpty() ? null : v.get(0);
    }
}
