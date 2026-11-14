package work.diffdev.kik.python;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.io.IOAccess;
import work.diffdev.kik.python.vfs.CCFileSystem;
import work.diffdev.kik.python.vfs.VPath;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide GraalPy state: the extracted runtime, one shared {@link Engine} for every context, and a spare
 * pre-initialised context so a script starts without paying interpreter start-up.
 */
public final class PythonRuntime {
    /** Where scripts see GraalPy's home (stdlib etc.); read-only, like the rest of /rom. Our own modules are in /rom/python. */
    public static final VPath PYTHON_HOME = VPath.of("/rom/graalpy");
    /** Server-wide shared libraries from config/kik-cc-python/libs. */
    public static final VPath SERVER_LIBS = VPath.of("/rom/python-libs");
    /** Host filesystem when the sandbox is disabled. */
    public static final VPath HOST_MOUNT = VPath.of("/host");

    private static final Path CACHE_DIR = Paths.get(System.getProperty("user.home"), ".kik-graalpy-cache");

    /** Sandboxed scripts may only touch Java members annotated with {@link HostAccess.Export}. */
    static final HostAccess SANDBOX_HOST_ACCESS = HostAccess.newBuilder()
        .allowAccessAnnotatedBy(HostAccess.Export.class)
        .allowArrayAccess(true)
        .allowListAccess(true)
        .build();

    public static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "KiK-Python-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicInteger RUNNING = new AtomicInteger();
    private static volatile Optional<RuntimeHome> home;
    private static volatile Engine engine;
    private static Spare spare;

    private PythonRuntime() {}

    /** A context together with the switchable pieces that are bound to a computer when the context is claimed. */
    public record Prepared(Context context, CCFileSystem fs, SwitchableOutput out, SwitchableOutput err, boolean sandboxed) {}

    private record Spare(Prepared prepared, boolean sandboxed) {}

    static {
        System.setProperty("polyglotimpl.DisableMultiReleaseCheck", "true");
        try {
            Path tmp = CACHE_DIR.resolve("tmp");
            Files.createDirectories(tmp);
            // sqlite-jdbc unpacks its native library here (/tmp is often noexec).
            if (System.getProperty("org.sqlite.tmpdir") == null) System.setProperty("org.sqlite.tmpdir", tmp.toString());
        } catch (Exception ignored) {}
    }

    public static synchronized Optional<RuntimeHome> home() {
        if (home != null) return home;
        Optional<RuntimeHome> found;
        try {
            found = Optional.of(RuntimeHome.prepare(CACHE_DIR));
        } catch (Throwable t) {
            KikCCPythonMod.LOGGER.error("[KiK CC-Python] Could not unpack the Python runtime; Python will not start.", t);
            found = Optional.empty();
        }
        home = found;
        return found;
    }

    public static synchronized Engine engine() {
        if (engine == null) {
            engine = Engine.newBuilder("python")
                // Plain OpenJDK runs Truffle without the optimising compiler; the warning would land in script output.
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        }
        return engine;
    }

    /** Claim an initialised context, building one if no spare is ready. Called on the script's worker thread. */
    public static Prepared claim(PythonConfig config) {
        boolean sandboxed = !config.sandboxDisabled;
        Prepared p = null;
        synchronized (PythonRuntime.class) {
            if (spare != null && spare.sandboxed() == sandboxed) {
                p = spare.prepared();
                spare = null;
            } else if (spare != null) {
                spare.prepared().context().close(true);
                spare = null;
            }
        }
        if (p == null) p = build(sandboxed);
        if (config.prewarm) prewarmAsync(sandboxed);
        return p;
    }

    public static void prewarmAsync(boolean sandboxed) {
        SCHEDULER.execute(() -> {
            synchronized (PythonRuntime.class) {
                if (spare != null) return;
            }
            try {
                Prepared p = build(sandboxed);
                synchronized (PythonRuntime.class) {
                    if (spare == null) {
                        spare = new Spare(p, sandboxed);
                        return;
                    }
                }
                p.context().close(true);
            } catch (Throwable t) {
                KikCCPythonMod.LOGGER.warn("[KiK CC-Python] Pre-warm failed: {}", t.toString());
            }
        });
    }

    public static synchronized void shutdown() {
        if (spare != null) {
            spare.prepared().context().close(true);
            spare = null;
        }
    }

    static Prepared build(boolean sandboxed) {
        var runtimeHome = home().orElseThrow(() -> new IllegalStateException("GraalPy runtime is not available"));
        Path root = runtimeHome.root().toAbsolutePath().normalize();

        List<CCFileSystem.HostMount> mounts = new ArrayList<>();
        mounts.add(new CCFileSystem.HostMount(SERVER_LIBS, PythonConfig.DIR.resolve("libs").toAbsolutePath(), true));
        mounts.add(new CCFileSystem.HostMount(PYTHON_HOME, root, true));
        if (!sandboxed) mounts.add(new CCFileSystem.HostMount(HOST_MOUNT, Paths.get("/").toAbsolutePath(), false));

        CCFileSystem fs = new CCFileSystem(mounts);
        SwitchableOutput out = new SwitchableOutput();
        SwitchableOutput err = new SwitchableOutput();

        Context.Builder b = Context.newBuilder("python")
            .engine(engine())
            .out(out)
            .err(err)
            .in(InputStream.nullInputStream())
            .option("python.PosixModuleBackend", "java")
            .option("python.DontWriteBytecodeFlag", "true")
            .option("python.CheckHashPycsMode", "never")
            .option("python.CoreHome", virtual(root, runtimeHome.coreHome()))
            .option("python.StdLibHome", virtual(root, runtimeHome.stdlibHome()))
            .option("python.CAPI", virtual(root, runtimeHome.coreHome()))
            .option("python.SysPrefix", PYTHON_HOME.toString())
            .option("python.SysBasePrefix", PYTHON_HOME.toString())
            .currentWorkingDirectory(VPath.ROOT);

        if (sandboxed) {
            b.allowAllAccess(false)
                .allowHostAccess(SANDBOX_HOST_ACCESS)
                .allowHostClassLookup(name -> false)
                .allowHostClassLoading(false)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowCreateThread(true)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowIO(IOAccess.newBuilder().fileSystem(fs).allowHostSocketAccess(false).build());
        } else {
            b.allowAllAccess(true)
                .allowIO(IOAccess.newBuilder().fileSystem(fs).allowHostSocketAccess(true).build());
        }

        Context ctx = b.build();
        // Pay the interpreter start-up now, outside the script's time.
        ctx.eval("python", "import sys, os, io, types, traceback, codeop, code, json, re, math");
        return new Prepared(ctx, fs, out, err, sandboxed);
    }

    private static String virtual(Path root, Path hostPath) {
        Path rel = root.relativize(hostPath.toAbsolutePath().normalize());
        VPath v = PYTHON_HOME;
        for (Path part : rel) v = (VPath) v.resolve(part.toString());
        return v.toString();
    }

    /** Reserve a slot for a new run; false if the server-wide limit is reached. */
    static boolean acquireSlot(PythonConfig config) {
        while (true) {
            int n = RUNNING.get();
            if (n >= config.maxSessions) return false;
            if (RUNNING.compareAndSet(n, n + 1)) return true;
        }
    }

    static void releaseSlot() {
        RUNNING.decrementAndGet();
    }
}
