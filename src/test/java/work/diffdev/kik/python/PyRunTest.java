package work.diffdev.kik.python;

import dan200.computercraft.core.filesystem.FileMount;
import dan200.computercraft.core.filesystem.FileSystem;
import dan200.computercraft.core.filesystem.WritableFileMount;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs real Python through {@link PyRun} with a Java stand-in for the Lua driver (rom/apis/python.lua) and a real CC
 * filesystem on a temp directory, with the mod's ROM mounted at /rom.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PyRunTest {
    private static final Charset CP1251 = Charset.forName("cp1251");

    @TempDir
    static Path disk;
    static FileSystem fs;

    @BeforeAll
    static void setUp() throws Exception {
        Properties p = new Properties();
        p.setProperty("limits.terminateGraceMs", "500");
        PythonConfig.override(p);
        fs = new FileSystem("hdd", new WritableFileMount(disk.toFile(), 10_000_000));
        fs.mount("rom", "rom", new FileMount(Paths.get("src/main/resources/data/computercraft/lua/rom").toAbsolutePath()));
    }

    @AfterAll
    static void tearDown() {
        PythonRuntime.shutdown();
    }

    /** What a finished run left behind. */
    record Result(boolean ok, String error, Object value, boolean printed, String out, int reads) {}

    /** The Lua side, in Java: draws output into a string, answers Lua calls from a tiny fake environment. */
    static final class Driver implements ComputerHost {
        final StringBuilder out = new StringBuilder();
        final ArrayDeque<String> input = new ArrayDeque<>();
        final Map<String, Object> refs = new HashMap<>();
        int reads;

        @Override
        public int id() {
            return 7;
        }

        @Override
        public FileSystem fileSystem() {
            return fs;
        }

        @Override
        public void queueEvent(String name, Object... args) {}

        Result run(PyRun.Mode mode, String source, String name, Consumer<PyRun> during, long duringAfterMs) throws Exception {
            PyRun run = new PyRun(this, PythonConfig.get(), new PyRun.Spec(mode, source, name, List.of("a1"), ""), () -> {});
            run.start();
            long started = System.nanoTime();
            boolean fired = during == null;
            while (true) {
                Object[] r = run.poll();
                switch ((String) r[0]) {
                    case "idle" -> {
                        if (!fired && System.nanoTime() - started > duringAfterMs * 1_000_000) {
                            fired = true;
                            during.accept(run);
                        }
                        Thread.sleep(5);
                    }
                    case "done" -> {
                        String err = r[2] == null ? null : TextCodec.decode((byte[]) r[2], CP1251);
                        return new Result((Boolean) r[1], err, r[3], (Boolean) r[4], out.toString(), reads);
                    }
                    case "out" -> draw(asList(r[1]));
                    case "read" -> {
                        reads++;
                        String line = input.poll();
                        run.reply(line == null ? new Object[] { false, "Terminated" } : new Object[] { true, lua(line) });
                    }
                    case "get" -> run.reply(get(str(r[1])));
                    case "call" -> run.reply(call(r[1], r[2] == null ? null : str(r[2]), asList(r[3])));
                    case "lua" -> run.reply(new Object[] { true, 42.0 });
                    default -> throw new AssertionError("unexpected request " + r[0]);
                }
            }
        }

        Result run(PyRun.Mode mode, String source) throws Exception {
            return run(mode, source, mode == PyRun.Mode.FILE ? source : "<string>", null, 0);
        }

        private void draw(List<Object> ops) {
            for (int i = 0; i < ops.size(); i += 2) {
                String op = (String) ops.get(i);
                Object arg = ops.get(i + 1);
                if (op.equals("t") || op.equals("e")) out.append(new String((byte[]) arg, CP1251));
            }
        }

        private Object[] get(String path) {
            if (path.equals("turtle")) {
                return new Object[] { true, ref(1, "table", List.of("forward", "getFuelLevel"), Map.of()) };
            }
            if (path.equals("colors")) {
                return new Object[] { true, ref(2, "table", List.of("combine"), Map.of("red", 16384.0, "white", 1.0)) };
            }
            return new Object[] { true, null };
        }

        private Object[] call(Object target, String field, List<Object> args) {
            if (Long.valueOf(1).equals(target) && "forward".equals(field)) return new Object[] { true, true };
            if (Long.valueOf(1).equals(target) && "getFuelLevel".equals(field)) return new Object[] { true, 80.0 };
            if (Long.valueOf(2).equals(target) && "combine".equals(field)) {
                long sum = 0;
                for (Object a : args) sum |= ((Number) a).longValue();
                return new Object[] { true, (double) sum };
            }
            if (target instanceof byte[] b && new String(b, StandardCharsets.ISO_8859_1).equals("echo")) {
                Object[] res = new Object[args.size() + 1];
                res[0] = true;
                for (int i = 0; i < args.size(); i++) res[i + 1] = args.get(i) instanceof byte[] s ? lua(new String(s, CP1251)) : args.get(i);
                return res;
            }
            return new Object[] { false, "attempt to call a nil value" };
        }

        private static Map<String, Object> ref(int id, String kind, List<String> keys, Map<String, Object> fields) {
            Map<Object, Object> k = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) k.put((double) (i + 1), keys.get(i));
            Map<String, Object> m = new HashMap<>();
            m.put(PyNative.REF_KEY, (double) id);
            m.put("kind", kind);
            m.put("keys", k);
            m.put("fields", new HashMap<>(fields));
            return m;
        }

        /** A Lua string as CC hands it to Java: one char per byte, here in the terminal encoding. */
        static String lua(String text) {
            return new String(text.getBytes(CP1251), StandardCharsets.ISO_8859_1);
        }

        @SuppressWarnings("unchecked")
        private static List<Object> asList(Object o) {
            if (o instanceof List<?> l) return (List<Object>) l;
            return new ArrayList<>();
        }

        private static String str(Object o) {
            return o instanceof byte[] b ? new String(b, CP1251) : String.valueOf(o);
        }
    }

    private static void write(String path, String text) throws Exception {
        Path p = disk.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    @Test
    void printsCyrillicAndArgs() throws Exception {
        Result r = new Driver().run(PyRun.Mode.CODE, "import sys\nprint('Привет', sys.argv[1:])\nprint('x' * 3, end='')");
        assertTrue(r.ok(), r.error());
        assertEquals("Привет ['a1']\nxxx", r.out());
    }

    @Test
    void tracebackHasLineNumbersAndIsPrintedOnce() throws Exception {
        write("prog/bad.py", "x = 1\n\ndef f():\n    return 1 / 0\n\nf()\n");
        Result r = new Driver().run(PyRun.Mode.FILE, "prog/bad.py");
        assertFalse(r.ok());
        assertTrue(r.printed(), "error should be marked as already printed");
        assertTrue(r.out().contains("line 4"), r.out());
        assertTrue(r.out().contains("line 6"), r.out());
        assertTrue(r.out().contains("ZeroDivisionError"), r.out());
        assertEquals(r.out().indexOf("ZeroDivisionError"), r.out().lastIndexOf("ZeroDivisionError"), r.out());
        assertFalse(r.out().contains("<kik>"), r.out());
    }

    @Test
    void importsFromScriptFolder() throws Exception {
        write("app/helper.py", "NAME = 'помощник'\n");
        write("app/main.py", "import helper\nprint(helper.NAME)\n");
        Result r = new Driver().run(PyRun.Mode.FILE, "app/main.py");
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertEquals("помощник\n", r.out());
    }

    @Test
    void readsCp1251SourceFile() throws Exception {
        Files.createDirectories(disk.resolve("enc"));
        Files.write(disk.resolve("enc/old.py"), "print('ёжик')\n".getBytes(CP1251));
        Result r = new Driver().run(PyRun.Mode.FILE, "enc/old.py");
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertEquals("ёжик\n", r.out());
    }

    @Test
    void replKeepsVariablesAndEchoesExpressions() throws Exception {
        Driver d = new Driver();
        d.input.add("x = 5");
        d.input.add("x * 2");
        d.input.add("def f(a):");
        d.input.add("    return a + x");
        d.input.add("");
        d.input.add("f(1)");
        d.input.add("'щ'");
        d.input.add("exit()");
        Result r = d.run(PyRun.Mode.REPL, "");
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertTrue(r.out().contains("\n10\n") || r.out().contains(">>> 10\n") || r.out().contains("10\n"), r.out());
        assertTrue(r.out().contains("6\n"), r.out());
        assertTrue(r.out().contains("'щ'"), r.out());
        assertEquals(8, r.reads());
    }

    @Test
    void terminateStopsBusyLoop() throws Exception {
        long t0 = System.nanoTime();
        Result r = new Driver().run(PyRun.Mode.CODE, "while True:\n    pass\n", "<string>",
            run -> run.event(new Object[] { "terminate" }), 1500);
        assertFalse(r.ok());
        assertEquals("Terminated", r.error());
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(30));
    }

    @Test
    void terminateKillsProgramThatSwallowsIt() throws Exception {
        long t0 = System.nanoTime();
        Result r = new Driver().run(PyRun.Mode.CODE, """
            while True:
                try:
                    while True:
                        pass
                except BaseException:
                    pass
            """, "<string>", run -> run.event(new Object[] { "terminate" }), 1500);
        assertFalse(r.ok());
        assertEquals("Terminated", r.error());
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(30));
    }

    @Test
    void timeLimitStopsProgram() throws Exception {
        Properties p = new Properties();
        p.setProperty("limits.terminateGraceMs", "500");
        p.setProperty("limits.maxRunSeconds", "2");
        PythonConfig.override(p);
        try {
            long t0 = System.nanoTime();
            Result r = new Driver().run(PyRun.Mode.CODE, "import time\nwhile True:\n    time.sleep(0.01)\n");
            assertFalse(r.ok());
            assertTrue(r.error().startsWith("Time limit exceeded"), r.error());
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(20));
        } finally {
            Properties d = new Properties();
            d.setProperty("limits.terminateGraceMs", "500");
            PythonConfig.override(d);
        }
    }

    @Test
    void terminateIsCatchableWhileSleeping() throws Exception {
        Result r = new Driver().run(PyRun.Mode.CODE,
            "import time\ntry:\n    time.sleep(30)\nexcept KeyboardInterrupt:\n    print('caught')\n", "<string>",
            run -> run.event(new Object[] { "terminate" }), 1500);
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertEquals("caught\n", r.out());
    }

    @Test
    void sleepPassesOtherErrorsThrough() throws Exception {
        Result r = new Driver().run(PyRun.Mode.CODE, "import time\ntime.sleep(0.05)\nraise ValueError('boom')\n");
        assertFalse(r.ok());
        assertTrue(r.out().contains("ValueError: boom"), r.out());
    }

    @Test
    void sandboxBlocksHostAccess() throws Exception {
        String code = """
            results = []
            try:
                import java
                java.type('java.lang.System')
                results.append('java')
            except Exception:
                pass
            try:
                open('/etc/passwd').read()
                results.append('etc')
            except OSError:
                pass
            try:
                import subprocess
                subprocess.run(['true'])
                results.append('proc')
            except Exception:
                pass
            try:
                import socket
                socket.create_connection(('127.0.0.1', 1), timeout=1)
                results.append('socket')
            except Exception:
                pass
            import os
            print(results, os.path.exists('/rom/python/requests.py'), 'HOME' in os.environ)
            """;
        Result r = new Driver().run(PyRun.Mode.CODE, code);
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertEquals("[] True False\n", r.out());
    }

    @Test
    void sqliteLivesOnTheComputerDisk() throws Exception {
        Result w = new Driver().run(PyRun.Mode.CODE, """
            import os, sqlite3
            try:
                sqlite3.connect('nodir/x.db')
            except sqlite3.OperationalError:
                print('nodir')
            os.makedirs('data', exist_ok=True)
            con = sqlite3.connect('data/test.db')
            con.execute('create table t (name text, n integer)')
            con.execute('insert into t values (?, ?)', ('кот', 3))
            con.execute('insert into t values (:name, :n)', {'name': 'пёс', 'n': 4})
            con.commit()
            con.close()
            """);
        assertTrue(w.ok(), w.error() + "\n" + w.out());
        assertEquals("nodir\n", w.out());
        assertTrue(Files.size(disk.resolve("data/test.db")) > 0);
        Result r = new Driver().run(PyRun.Mode.CODE, """
            import sqlite3
            con = sqlite3.connect('data/test.db')
            con.row_factory = sqlite3.Row
            rows = con.execute('select name, n from t order by n').fetchall()
            print([(row['name'], row[1]) for row in rows])
            try:
                con.execute('select nope from t')
            except sqlite3.OperationalError as e:
                print('err', 'nope' in str(e))
            """);
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertEquals("[('кот', 3), ('пёс', 4)]\nerr True\n", r.out());
    }

    @Test
    void callsLuaApis() throws Exception {
        Result r = new Driver().run(PyRun.Mode.CODE, """
            import turtle, colors
            from cc import lua
            print(turtle.forward(), turtle.get_fuel_level(), colors.combine(colors.red, colors.white), lua.eval('return 1'))
            """);
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertEquals("True 80 16385 42\n", r.out());
    }

    @Test
    void runsOurPipAsModule() throws Exception {
        Result r = new Driver().run(PyRun.Mode.CODE, """
            import runpy, sys
            sys.argv = ['pip', '--version']
            try:
                runpy.run_module('pip', run_name='__main__', alter_sys=True)
            except SystemExit:
                pass
            import pip
            print(pip.__file__)
            """);
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertTrue(r.out().endsWith("/rom/python/pip.py\n"), r.out());
    }

    @Test
    void evalReturnsValue() throws Exception {
        Result r = new Driver().run(PyRun.Mode.EVAL, "{'a': [1, 2.5, 'ж']}");
        assertTrue(r.ok(), r.error());
        Map<?, ?> m = (Map<?, ?>) r.value();
        Map<?, ?> list = (Map<?, ?>) m.get("a");
        assertEquals(3, list.size());
        assertEquals(2.5, list.get(2.0));
        assertEquals("ж", new String((byte[]) list.get(3.0), CP1251));
    }

    /** Answers with headers and part of the body, then stalls. */
    private static com.sun.net.httpserver.HttpServer stallingServer() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, 100);
            ex.getResponseBody().write("0123456789".getBytes(StandardCharsets.US_ASCII));
            ex.getResponseBody().flush();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {}
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static void allowLocalHttp() {
        Properties p = new Properties();
        p.setProperty("limits.terminateGraceMs", "500");
        p.setProperty("http.enabled", "true");
        p.setProperty("http.rules", "allow 127.0.0.1; deny *");
        PythonConfig.override(p);
    }

    private static void defaultConfig() {
        Properties p = new Properties();
        p.setProperty("limits.terminateGraceMs", "500");
        PythonConfig.override(p);
    }

    @Test
    void stalledBodyTimesOut() throws Exception {
        var server = stallingServer();
        allowLocalHttp();
        try {
            long t0 = System.nanoTime();
            Result r = new Driver().run(PyRun.Mode.CODE, """
                import requests
                try:
                    requests.get('http://127.0.0.1:%d/', timeout=1.5)
                    print('got it')
                except requests.RequestException as e:
                    print(type(e).__name__)
                """.formatted(server.getAddress().getPort()));
            assertTrue(r.ok(), r.error() + "\n" + r.out());
            assertEquals("Timeout\n", r.out());
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(15));
        } finally {
            defaultConfig();
            server.stop(0);
        }
    }

    @Test
    void terminateStopsStalledDownload() throws Exception {
        var server = stallingServer();
        allowLocalHttp();
        try {
            long t0 = System.nanoTime();
            Result r = new Driver().run(PyRun.Mode.CODE,
                "import requests\nrequests.get('http://127.0.0.1:%d/')\n".formatted(server.getAddress().getPort()),
                "<string>", run -> run.event(new Object[] { "terminate" }), 1500);
            assertFalse(r.ok());
            assertEquals("Terminated", r.error());
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(15));
        } finally {
            defaultConfig();
            server.stop(0);
        }
    }

    @Test
    void requestsFollowCcRules() throws Exception {
        Result r = new Driver().run(PyRun.Mode.CODE, """
            import requests
            try:
                requests.get('http://127.0.0.1:1/')
                print('connected')
            except requests.ConnectionError as e:
                print('denied', e)
            """);
        assertTrue(r.ok(), r.error() + "\n" + r.out());
        assertTrue(r.out().startsWith("denied"), r.out());
    }
}
