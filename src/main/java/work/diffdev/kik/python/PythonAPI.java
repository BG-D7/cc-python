package work.diffdev.kik.python;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.core.apis.IAPIEnvironment;
import dan200.computercraft.core.filesystem.FileSystem;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The native half of the Python API, one instance per computer, exposed to Lua as {@code _python}. The Lua half
 * ({@code rom/apis/python.lua}) drives runs with it; programs should use the {@code python} API instead.
 */
public final class PythonAPI implements ILuaAPI {
    private static final Object[] UNKNOWN = { "done", false, "No such Python run", null, false };

    private final ComputerHost host;
    private final Map<Integer, PyRun> runs = new ConcurrentHashMap<>();

    PythonAPI(ComputerHost host) {
        this.host = host;
    }

    public static PythonAPI create(IComputerSystem system) {
        return new PythonAPI(new SystemHost(system));
    }

    @Override
    public String[] getNames() {
        return new String[] { "_python" };
    }

    @Override
    public void update() {
        for (PyRun run : runs.values()) {
            if (run.isOrphaned()) run.kill("The Lua side of this Python program stopped");
        }
    }

    @Override
    public void shutdown() {
        for (PyRun run : runs.values()) run.kill("Computer shut down");
        runs.clear();
    }

    /**
     * start(mode, source, name, argv, cwd) -> id | nil, error. mode is "code", "file", "repl" or "eval"; for "file"
     * the source is ignored and the script is read from name (relative to cwd).
     */
    @LuaFunction
    public final Object[] start(IArguments args) throws LuaException {
        PythonConfig config = PythonConfig.get();
        PyRun.Mode mode;
        try {
            mode = PyRun.Mode.valueOf(args.getString(0).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LuaException("Bad mode '" + args.getString(0) + "'");
        }
        String source = text(args.optString(1).orElse(""), config);
        String name = text(args.optString(2).orElse(mode == PyRun.Mode.FILE ? "" : "<string>"), config);
        List<String> argv = new ArrayList<>();
        for (Object a : args.optTable(3).map(PythonAPI::sequence).orElse(List.of())) {
            argv.add(a instanceof String s ? text(s, config) : a instanceof Double d && d == Math.rint(d) ? Long.toString(d.longValue()) : String.valueOf(a));
        }
        String cwd = text(args.optString(4).orElse(""), config);

        runs.values().removeIf(PyRun::isFinished);
        if (runs.size() >= config.maxSessionsPerComputer) {
            return new Object[] { null, "Too many Python programs running on this computer (limit " + config.maxSessionsPerComputer + ")" };
        }
        if (PythonRuntime.home().isEmpty()) return new Object[] { null, "The Python runtime failed to load; see the server log" };
        if (!PythonRuntime.acquireSlot(config)) {
            return new Object[] { null, "Too many Python programs running on the server (limit " + config.maxSessions + ")" };
        }
        // A finished run stays registered until the driver collects its result (or start() prunes it).
        PyRun run = new PyRun(host, config, new PyRun.Spec(mode, source, name, argv, cwd), PythonRuntime::releaseSlot);
        runs.put(run.id, run);
        try {
            run.start();
        } catch (Throwable t) {
            runs.remove(run.id);
            PythonRuntime.releaseSlot();
            throw new LuaException("Cannot start Python: " + t.getMessage());
        }
        return new Object[] { run.id };
    }

    /** poll(id) -> kind, ... (see PyRun#poll). After "done" the run is forgotten. */
    @LuaFunction
    public final MethodResult poll(int id) {
        PyRun run = runs.get(id);
        if (run == null) return MethodResult.of(UNKNOWN);
        Object[] r = run.poll();
        if ("done".equals(r[0])) runs.remove(id);
        return MethodResult.of(r);
    }

    /** reply(id, values...): the answer to the request poll() just handed out. */
    @LuaFunction
    public final void reply(IArguments args) throws LuaException {
        PyRun run = runs.get(args.getInt(0));
        if (run != null) run.reply(args.drop(1).getAll());
    }

    /** event(id, name, args...): a computer event for the script. */
    @LuaFunction
    public final void event(IArguments args) throws LuaException {
        PyRun run = runs.get(args.getInt(0));
        if (run != null) run.event(args.drop(1).getAll());
    }

    /** kill(id[, reason]): stop the run now. */
    @LuaFunction
    public final void kill(int id, java.util.Optional<String> reason) {
        PyRun run = runs.get(id);
        if (run != null) run.kill(reason.orElse("Terminated"));
    }

    @LuaFunction
    public final boolean sandboxed() {
        return !PythonConfig.get().sandboxDisabled;
    }

    @LuaFunction
    public final String version() {
        return "Python 3.12 (GraalPy 25.0) for CC: Tweaked";
    }

    private static String text(String lua, PythonConfig config) {
        return TextCodec.decodeLua(lua, config.luaCharset);
    }

    private static List<Object> sequence(Map<?, ?> table) {
        List<Object> out = new ArrayList<>();
        for (int i = 1; table.containsKey((double) i); i++) out.add(table.get((double) i));
        return out;
    }

    /** {@link ComputerHost} over CC's computer system. */
    private static final class SystemHost implements ComputerHost {
        private final IComputerSystem system;
        private volatile IAPIEnvironment env;

        SystemHost(IComputerSystem system) {
            this.system = system;
        }

        @Override
        public int id() {
            return system.getID();
        }

        @Override
        public FileSystem fileSystem() {
            IAPIEnvironment e = environment();
            return e == null ? null : e.getFileSystem();
        }

        @Override
        public void queueEvent(String name, Object... args) {
            IAPIEnvironment e = environment();
            if (e != null) e.queueEvent(name, args);
            else system.queueEvent(name, args);
        }

        /** CC keeps the environment (and with it the filesystem) private; the API has no other way to reach it. */
        private IAPIEnvironment environment() {
            IAPIEnvironment e = env;
            if (e != null) return e;
            for (Class<?> c = system.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (IAPIEnvironment.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            env = e = (IAPIEnvironment) f.get(system);
                            return e;
                        } catch (ReflectiveOperationException | RuntimeException ex) {
                            KikCCPythonMod.LOGGER.warn("[KiK CC-Python] Cannot reach the computer environment: {}", ex.toString());
                        }
                    }
                }
            }
            return null;
        }
    }
}
