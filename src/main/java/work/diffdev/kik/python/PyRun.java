package work.diffdev.kik.python;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import work.diffdev.kik.python.vfs.CCFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Python execution on one computer.
 *
 * <p>Python runs on its own worker thread and never blocks the CC computer thread. The Lua driver
 * ({@code rom/apis/python.lua}) loops on {@link #poll()}: it draws output, runs Lua calls the script asked for
 * (turtle, peripherals, read() ...) and answers with {@link #reply}, and while idle forwards every computer event via
 * {@link #event}. A script waiting for the driver blocks only its own thread.
 *
 * <p>Stopping: Ctrl+T is delivered as a "terminate" event. A script waiting for input, an event or sleep gets
 * {@code KeyboardInterrupt} and may handle it; a busy one is interrupted after the grace period (or on a second
 * Ctrl+T) through {@link Context#interrupt}, which unwinds guest code and cannot be caught. Shutdown, time limits
 * and a vanished driver cancel the whole context.
 */
public final class PyRun {
    public enum Mode { CODE, FILE, REPL, EVAL }

    public record Spec(Mode mode, String source, String name, List<String> argv, String cwd) {}

    static final String WAKE_EVENT = "kik_python";
    private static final Object[] TERMINATE = { "terminate" };
    private static final long OUTPUT_HIGH_WATER = 64 * 1024;
    private static final long ORPHAN_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long WORKER_STACK = 64L * 1024 * 1024;
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ExecutorService REAPER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "KiK-Python-Reaper");
        t.setDaemon(true);
        return t;
    });

    final int id = IDS.incrementAndGet();
    final ComputerHost host;
    final PythonConfig config;
    final Spec spec;
    private final Runnable onFinish;
    final OutputOps stdout;
    final OutputOps stderr;

    // --- state shared between the worker and the computer thread; guarded by `this` ---------------------------
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    private Request inFlight;
    private final ArrayDeque<Object[]> inbox = new ArrayDeque<>();
    private boolean wakePending;
    private long pendingOutput;
    private long terminateSince;
    private boolean interruptRequested;
    private boolean killed;
    private String killReason;
    private Object[] result;
    private long lastPoll = System.nanoTime();
    private Context context;
    private CCFileSystem fs;
    private final List<AutoCloseable> resources = new ArrayList<>();

    private static final class Request {
        final String kind;
        final Object[] payload;
        final List<Object> ops;
        final long queuedAt = System.nanoTime();
        long bytes;
        Object[] reply;
        boolean done;

        Request(String kind, Object[] payload) {
            this.kind = kind;
            this.payload = payload;
            this.ops = null;
        }

        Request(List<Object> ops, long bytes) {
            this.kind = "out";
            this.payload = null;
            this.ops = ops;
            this.bytes = bytes;
        }
    }

    /** The script was cancelled; unwinds the worker out of a bridge wait. */
    static final class ScriptKilled extends RuntimeException {
        ScriptKilled(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Ctrl+T is interrupting the script; unwinds a bridge wait so the pending context interrupt can fire. */
    static final class ScriptInterrupted extends RuntimeException {
        ScriptInterrupted() {
            super("KeyboardInterrupt", null, false, false);
        }
    }

    PyRun(ComputerHost host, PythonConfig config, Spec spec, Runnable onFinish) {
        this.host = host;
        this.config = config;
        this.spec = spec;
        this.onFinish = onFinish;
        this.stdout = new OutputOps(config.luaCharset, false);
        this.stderr = new OutputOps(config.luaCharset, true);
    }

    void start() {
        Thread worker = new Thread(null, this::runWorker, "KiK-Python-" + host.id() + "-" + id, WORKER_STACK);
        worker.setDaemon(true);
        worker.start();
        if (config.maxRunSeconds > 0 && spec.mode() != Mode.REPL) {
            PythonRuntime.SCHEDULER.schedule(() -> kill("Time limit exceeded (" + config.maxRunSeconds + " s)"),
                config.maxRunSeconds, TimeUnit.SECONDS);
        }
    }

    // ======================================= Lua driver side (computer thread) ==================================

    /** Next thing the driver should do. Never blocks. */
    synchronized Object[] poll() {
        lastPoll = System.nanoTime();
        Request r = requests.poll();
        if (r != null) {
            if (r.ops != null) {
                pendingOutput -= r.bytes;
                notifyAll();
                return new Object[] { "out", r.ops };
            }
            inFlight = r;
            Object[] out = new Object[r.payload.length + 1];
            out[0] = r.kind;
            System.arraycopy(r.payload, 0, out, 1, r.payload.length);
            return out;
        }
        if (result != null) return result;
        wakePending = false;
        return new Object[] { "idle" };
    }

    synchronized void reply(Object[] values) {
        if (inFlight == null) return;
        inFlight.reply = values;
        inFlight.done = true;
        inFlight = null;
        notifyAll();
    }

    synchronized boolean isFinished() {
        return result != null;
    }

    /** The driver stopped polling (its program died) while it was not busy with one of our requests. */
    synchronized boolean isOrphaned() {
        return result == null && inFlight == null && System.nanoTime() - lastPoll > ORPHAN_NANOS;
    }

    void event(Object[] ev) {
        if (ev.length == 0 || WAKE_EVENT.equals(ev[0])) return;
        if ("terminate".equals(ev[0])) {
            onTerminate();
            return;
        }
        synchronized (this) {
            if (result != null || inbox.size() >= config.maxEventQueue) return; // CC drops events past its queue too
            inbox.add(ev);
            notifyAll();
        }
    }

    private static final Set<Object> INPUT_EVENTS = Set.of("char", "key", "key_up", "paste");

    /**
     * Keyboard events the script has not consumed yet (typed while it was starting). The driver re-queues them
     * before read(), so typeahead reaches the prompt the way it does for a Lua program.
     */
    synchronized List<Object> takeTypeahead() {
        List<Object> out = new ArrayList<>();
        for (Iterator<Object[]> it = inbox.iterator(); it.hasNext(); ) {
            Object[] ev = it.next();
            if (INPUT_EVENTS.contains(ev[0])) {
                out.add(Arrays.asList(ev));
                it.remove();
            }
        }
        return out;
    }

    private void onTerminate() {
        long since;
        synchronized (this) {
            if (result != null) return;
            if (terminateSince != 0) {
                since = 0; // second Ctrl+T: stop now
            } else {
                terminateSince = since = System.nanoTime();
                inbox.add(TERMINATE);
                notifyAll();
            }
        }
        if (since == 0) {
            interruptScript();
            return;
        }
        PythonRuntime.SCHEDULER.schedule(() -> {
            synchronized (this) {
                if (terminateSince != since || result != null) return;
            }
            interruptScript();
        }, config.terminateGraceMs, TimeUnit.MILLISECONDS);
    }

    /** Interrupt running guest code (KeyboardInterrupt that cannot be caught). The context stays usable. */
    private void interruptScript() {
        Context ctx;
        synchronized (this) {
            if (result != null || interruptRequested) return;
            interruptRequested = true;
            ctx = context;
            notifyAll();
        }
        if (ctx == null) {
            kill("Terminated");
            return;
        }
        REAPER.execute(() -> {
            try {
                ctx.interrupt(Duration.ofSeconds(5));
            } catch (TimeoutException e) {
                kill("Terminated (script did not respond)");
            } catch (Exception ignored) {
                // Context already closed.
            }
        });
    }

    /** Cancel the whole run (context closed). Safe from any thread. */
    void kill(String reason) {
        Context ctx;
        synchronized (this) {
            if (killed || result != null) return;
            killed = true;
            killReason = reason;
            ctx = context;
            notifyAll();
        }
        if (ctx != null) REAPER.execute(() -> closeQuietly(ctx));
    }

    private static void closeQuietly(Context ctx) {
        try {
            ctx.close(true);
        } catch (Throwable ignored) {}
    }


    // ======================================= Python side (worker thread) ========================================

    private void checkAlive() {
        if (killed) throw new ScriptKilled(killReason);
        if (interruptRequested) throw new ScriptInterrupted();
    }

    private void waitBriefly() {
        try {
            wait(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptKilled("Interrupted");
        }
    }

    private void wake() {
        boolean send;
        synchronized (this) {
            send = !wakePending;
            wakePending = true;
        }
        if (send) host.queueEvent(WAKE_EVENT, id);
    }

    /** Ask the driver to do something and wait for its answer. */
    Object[] request(String kind, Object... payload) {
        Request r = new Request(kind, payload);
        synchronized (this) {
            checkAlive();
            requests.add(r);
        }
        wake();
        synchronized (this) {
            while (!r.done) {
                checkAlive();
                if (requests.contains(r) && System.nanoTime() - r.queuedAt > ORPHAN_NANOS && lastPoll < r.queuedAt) {
                    kill("The Lua side of this Python program stopped");
                    checkAlive();
                }
                waitBriefly();
            }
            return r.reply;
        }
    }

    void write(String text, boolean err) {
        List<Object> ops = new ArrayList<>();
        synchronized (this) {
            (err ? stderr : stdout).feed(text, ops);
        }
        if (ops.isEmpty()) return;
        long bytes = 0;
        for (Object o : ops) if (o instanceof byte[] b) bytes += b.length;
        synchronized (this) {
            checkAlive();
            Request last = requests.peekLast();
            if (last != null && last.ops != null) {
                last.ops.addAll(ops);
                last.bytes += bytes;
            } else {
                requests.add(new Request(ops, bytes));
            }
            pendingOutput += bytes;
        }
        wake();
        synchronized (this) {
            while (pendingOutput > OUTPUT_HIGH_WATER) {
                checkAlive();
                waitBriefly();
            }
        }
    }

    /** Next event matching the filter; "terminate" always matches. */
    synchronized Object[] pullEvent(String filter) {
        while (true) {
            checkAlive();
            Object[] ev = inbox.poll();
            if (ev == null) {
                waitBriefly();
                continue;
            }
            if (ev == TERMINATE) {
                terminateSince = 0;
                return ev;
            }
            if (filter == null || filter.equals(ev[0])) return ev;
        }
    }

    /** Sleep without blocking the computer. Returns true if Ctrl+T cut the sleep short. */
    synchronized boolean sleep(double seconds) {
        long deadline = System.nanoTime() + (long) (Math.max(0, seconds) * 1e9);
        while (true) {
            checkAlive();
            if (terminateSince != 0) {
                inbox.remove(TERMINATE);
                terminateSince = 0;
                return true;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            try {
                wait(Math.max(1, Math.min(100, left / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ScriptKilled("Interrupted");
            }
        }
    }

    /** The filesystem the script sees (for bridges that store data on the computer's disk). */
    public CCFileSystem fileSystem() {
        return fs;
    }

    /** Close this when the run ends (sockets, databases). */
    public synchronized void track(AutoCloseable resource) {
        if (result != null) {
            closeQuietly(resource);
            return;
        }
        resources.add(resource);
    }

    public synchronized void untrack(AutoCloseable resource) {
        resources.remove(resource);
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Throwable ignored) {}
    }

    /** Throw if the run is being stopped. For bridge code that waits on its own. */
    public synchronized void checkStopping() {
        checkAlive();
    }

    /** Wait for a future on the worker thread, giving up on timeout (timeoutMs <= 0: none) or when the run stops. */
    public <T> T await(CompletableFuture<T> future, long timeoutMs) throws ExecutionException, TimeoutException {
        long deadline = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000 : Long.MAX_VALUE;
        try {
            while (true) {
                checkStopping();
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (System.nanoTime() > deadline) throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ScriptKilled("Interrupted");
                }
            }
        } catch (RuntimeException | ExecutionException | TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private synchronized void clearInterrupt() {
        interruptRequested = false;
        terminateSince = 0;
        inbox.remove(TERMINATE);
    }

    // ======================================= worker =============================================================

    private void runWorker() {
        Object[] done;
        Context ctx = null;
        try {
            PythonRuntime.Prepared p = PythonRuntime.claim(config);
            ctx = p.context();
            synchronized (this) {
                context = ctx;
                if (killed) throw new ScriptKilled(killReason);
            }
            p.fs().bind(host.fileSystem());
            fs = p.fs();
            p.out().bind(s -> write(s, false));
            p.err().bind(s -> write(s, true));

            PyNative nativeApi = new PyNative(this, p.sandboxed());
            Value boot = loadBoot(ctx);
            Value session = boot.invokeMember("start", nativeApi, spec.mode().name(), spec.source(), spec.name(),
                spec.argv().toArray(), spec.cwd());

            if (spec.mode() == Mode.REPL) {
                done = runRepl(session);
            } else {
                Value r = session.invokeMember("run");
                Object value = spec.mode() == Mode.EVAL ? nativeApi.toLua(r.getArrayElement(3)) : null;
                done = finished(r.getArrayElement(0).asBoolean(), str(r.getArrayElement(1)), value, r.getArrayElement(2).asBoolean());
            }
        } catch (PolyglotException e) {
            done = fromPolyglot(e);
        } catch (ScriptKilled e) {
            done = finished(false, e.getMessage(), null);
        } catch (Throwable t) {
            KikCCPythonMod.LOGGER.error("[KiK CC-Python] Internal error in Python run", t);
            done = finished(false, "Internal error: " + t, null);
        }

        List<AutoCloseable> open;
        synchronized (this) {
            result = done;
            open = new ArrayList<>(resources);
            resources.clear();
            notifyAll();
        }
        for (AutoCloseable c : open) closeQuietly(c);
        wake();
        if (ctx != null) {
            Context c = ctx;
            REAPER.execute(() -> closeQuietly(c));
        }
        onFinish.run();
    }

    private Object[] runRepl(Value session) {
        while (true) {
            try {
                if (!session.invokeMember("step").asBoolean()) return finished(true, null, null);
            } catch (PolyglotException e) {
                if (e.isInterrupted() || (!e.isCancelled() && isOurInterrupt(e))) {
                    clearInterrupt();
                    write("KeyboardInterrupt\n", true);
                    continue;
                }
                return fromPolyglot(e);
            }
        }
    }

    private boolean isOurInterrupt(PolyglotException e) {
        synchronized (this) {
            return interruptRequested && !killed;
        }
    }

    private Object[] fromPolyglot(PolyglotException e) {
        String reason;
        synchronized (this) {
            reason = killed ? killReason : null;
        }
        if (reason != null) return finished(false, reason, null);
        if (e.isInterrupted() || e.isCancelled()) return finished(false, "Terminated", null);
        if (e.isHostException() && e.asHostException() instanceof ScriptKilled k) return finished(false, k.getMessage(), null);
        if (e.isHostException() && e.asHostException() instanceof ScriptInterrupted) return finished(false, "Terminated", null);
        if (e.isGuestException()) {
            // The boot module reports script errors itself; reaching here means it failed.
            KikCCPythonMod.LOGGER.warn("[KiK CC-Python] Python bootstrap error: {}", e.getMessage());
            return finished(false, "Python error: " + e.getMessage(), null);
        }
        KikCCPythonMod.LOGGER.error("[KiK CC-Python] Internal GraalPy error", e);
        return finished(false, "Internal error: " + e.getMessage(), null);
    }

    private Object[] finished(boolean ok, String error, Object value) {
        return finished(ok, error, value, false);
    }

    /** The "done" tuple returned by poll(): ok, error message, value, and whether the error is already on screen. */
    private Object[] finished(boolean ok, String error, Object value, boolean printed) {
        return new Object[] { "done", ok, error == null ? null : TextCodec.encode(error, config.luaCharset), value, printed };
    }

    private static String str(Value v) {
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Value loadBoot(Context ctx) throws IOException {
        String code;
        try (InputStream in = PyRun.class.getResourceAsStream("/python_modules/_kik_boot.py")) {
            if (in == null) throw new IOException("_kik_boot.py missing from the mod jar");
            code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Source src = Source.newBuilder("python", code, "<kik>").build();
        ctx.eval(src);
        return ctx.getBindings("python").getMember("_kik_boot_api");
    }
}
