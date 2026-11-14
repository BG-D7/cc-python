package work.diffdev.ccpython;

import dan200.computercraft.core.CoreConfig;
import dan200.computercraft.core.apis.http.options.Action;
import dan200.computercraft.core.apis.http.options.AddressRule;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Properties;

/**
 * Mod configuration, read from {@code config/ccpython/config.properties}. The file is created with comments on
 * first start and re-read when it changes, so most keys apply to the next script without a restart.
 */
public final class PythonConfig {
    public static final Path DIR = Paths.get("config", "ccpython");
    public static final Path FILE = DIR.resolve("config.properties");

    private static final String DEFAULT_FILE = """
        # CC: Python configuration. Changes apply to the next script that starts.

        # DANGER ("shoot yourself in the foot"). true turns the Python sandbox off: scripts get Java interop
        # (import java), the host filesystem under /host, raw sockets, subprocesses and native code - that is,
        # full control of the server. Leave false on any server where players you do not fully trust can code.
        sandbox.disable = false

        # requests / websocket. "inherit" takes the value from CC: Tweaked's own config (http.enabled,
        # http.websocket_enabled, http.rules), so Python obeys exactly the same whitelist/blacklist as Lua.
        http.enabled = inherit
        websocket.enabled = inherit
        # "inherit", or your own rules: "action host[:port]" separated by ';', first match wins.
        # host is a domain pattern (*.example.com), an IP/CIDR, $private (LAN, loopback, link-local) or *.
        # Example: deny $private; allow api.example.com; allow *.wikipedia.org; deny *
        http.rules = inherit

        # Encoding of Lua strings and of the terminal font. cp1251 = kik-pack Cyrillic font,
        # latin1 = stock CC font, utf-8 = pass UTF-8 bytes through unchanged.
        text.encoding = cp1251

        # Wall-clock limit for one script run, seconds (0 = unlimited). The REPL is exempt.
        limits.maxRunSeconds = 0
        # After Ctrl+T a script that does not react (busy loop without input/sleep) is killed after this many ms.
        limits.terminateGraceMs = 1500
        # Concurrent Python scripts on the whole server / on one computer.
        limits.maxSessions = 32
        limits.maxSessionsPerComputer = 4
        # Events buffered for a script that is busy and not reading them (CC itself keeps 256).
        limits.maxEventQueue = 256

        # Keep one initialised interpreter ready so the next script starts instantly.
        performance.prewarm = true
        """;

    private static volatile PythonConfig current = defaults();
    private static volatile long loadedMtime = Long.MIN_VALUE;

    public final boolean sandboxDisabled;
    private final String httpEnabled;
    private final String websocketEnabled;
    private final List<AddressRule> httpRules; // null = inherit
    public final Charset luaCharset;
    public final long maxRunSeconds;
    public final long terminateGraceMs;
    public final int maxSessions;
    public final int maxSessionsPerComputer;
    public final int maxEventQueue;
    public final boolean prewarm;

    private PythonConfig(Properties p) {
        sandboxDisabled = bool(p, "sandbox.disable", false);
        httpEnabled = str(p, "http.enabled", "inherit");
        websocketEnabled = str(p, "websocket.enabled", "inherit");
        httpRules = parseRules(str(p, "http.rules", "inherit"));
        luaCharset = charset(str(p, "text.encoding", "cp1251"));
        maxRunSeconds = num(p, "limits.maxRunSeconds", 0);
        terminateGraceMs = num(p, "limits.terminateGraceMs", 1500);
        maxSessions = (int) num(p, "limits.maxSessions", 32);
        maxSessionsPerComputer = (int) num(p, "limits.maxSessionsPerComputer", 4);
        maxEventQueue = (int) Math.max(16, num(p, "limits.maxEventQueue", 256));
        prewarm = bool(p, "performance.prewarm", true);
    }

    public static PythonConfig defaults() {
        return new PythonConfig(new Properties());
    }

    public static PythonConfig get() {
        reloadIfChanged();
        return current;
    }

    /** For tests: use the given config instead of the file. */
    public static void override(Properties p) {
        current = new PythonConfig(p);
        loadedMtime = Long.MAX_VALUE;
    }

    private static synchronized void reloadIfChanged() {
        if (loadedMtime == Long.MAX_VALUE) return;
        try {
            if (!Files.exists(FILE)) {
                Files.createDirectories(DIR);
                Files.writeString(FILE, DEFAULT_FILE, StandardCharsets.UTF_8);
            }
            long mtime = Files.getLastModifiedTime(FILE).toMillis();
            if (mtime == loadedMtime) return;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            current = new PythonConfig(p);
            loadedMtime = mtime;
        } catch (IOException | RuntimeException e) {
            CCPythonMod.LOGGER.warn("[CC: Python] Cannot read {}: {}", FILE, e.toString());
            loadedMtime = Long.MIN_VALUE + 1;
        }
    }

    public boolean httpEnabled() {
        return httpEnabled.equals("inherit") ? CoreConfig.httpEnabled : Boolean.parseBoolean(httpEnabled);
    }

    public boolean websocketEnabled() {
        if (!httpEnabled()) return false;
        return websocketEnabled.equals("inherit") ? CoreConfig.httpWebsocketEnabled : Boolean.parseBoolean(websocketEnabled);
    }

    public List<AddressRule> httpRules() {
        return httpRules != null ? httpRules : CoreConfig.httpRules;
    }

    static List<AddressRule> parseRules(String spec) {
        if (spec.equalsIgnoreCase("inherit")) return null;
        List<AddressRule> rules = new ArrayList<>();
        for (String raw : spec.split(";")) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            String[] parts = entry.split("\\s+", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Bad http rule '" + entry + "'");
            Action action = switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "allow" -> Action.ALLOW;
                case "deny" -> Action.DENY;
                default -> throw new IllegalArgumentException("Bad http rule action '" + parts[0] + "'");
            };
            String host = parts[1].trim();
            OptionalInt port = OptionalInt.empty();
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon && host.substring(colon + 1).matches("\\d+")) {
                port = OptionalInt.of(Integer.parseInt(host.substring(colon + 1)));
                host = host.substring(0, colon);
            }
            rules.add(AddressRule.parse(host, port, action.toPartial()));
        }
        return List.copyOf(rules);
    }

    private static Charset charset(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "utf8", "utf-8" -> StandardCharsets.UTF_8;
            case "latin1", "latin-1", "iso-8859-1" -> StandardCharsets.ISO_8859_1;
            default -> Charset.forName(name);
        };
    }

    private static String str(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static boolean bool(Properties p, String key, boolean def) {
        return Boolean.parseBoolean(str(p, key, Boolean.toString(def)));
    }

    private static long num(Properties p, String key, long def) {
        try {
            return Long.parseLong(str(p, key, Long.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
