package work.diffdev.kik.python;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.PolyglotException;
import fr.oesphy.pyfish.runtime.GraalPyRuntimeResources;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.core.apis.IAPIEnvironment;
import dan200.computercraft.core.apis.OSAPI;
import dan200.computercraft.core.apis.RedstoneAPI;
import dan200.computercraft.shared.turtle.apis.TurtleAPI;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.filesystem.FileSystem;
import work.diffdev.kik.python.bridge.*;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

public class PythonSession {
    private final IComputerSystem system;
    private final IAPIEnvironment env;
    private final Terminal terminal;
    private final FileSystem fs;
    private final TurtleAPI turtleApi;
    private final RedstoneAPI redstoneApi;
    private final OSAPI osApi;
    private static Optional<GraalPyRuntimeResources.RuntimeHome> runtimeHome = null;

    public PythonSession(IComputerSystem system, IAPIEnvironment env, Terminal terminal, 
                         FileSystem fs, TurtleAPI turtleApi, RedstoneAPI redstoneApi, OSAPI osApi) {
        this.system = system;
        this.env = env;
        this.terminal = terminal;
        this.fs = fs;
        this.turtleApi = turtleApi;
        this.redstoneApi = redstoneApi;
        this.osApi = osApi;
    }

    public static synchronized Optional<GraalPyRuntimeResources.RuntimeHome> getRuntimeHome() {
        if (runtimeHome == null) {
            try {
                Path cacheDir = Paths.get(System.getProperty("user.home"), ".kik-graalpy-cache");
                Path cacheTmp = cacheDir.resolve("tmp");
                Files.createDirectories(cacheTmp);
                System.setProperty("org.sqlite.tmpdir", cacheTmp.toString());
                System.setProperty("polyglotimpl.DisableMultiReleaseCheck", "true");
                // 1. Try finding enclosing JAR from this mod
                try {
                    var uri = KikCCPythonMod.class.getProtectionDomain().getCodeSource().getLocation().toURI();
                    Path jarPath = Paths.get(uri);
                    if (Files.isRegularFile(jarPath)) {
                        runtimeHome = GraalPyRuntimeResources.prepare(jarPath, cacheDir);
                        if (runtimeHome.isPresent()) return runtimeHome;
                    }
                } catch (Throwable ignored) {}

                // 2. Try GraalPyRuntimeResources CodeSource
                try {
                    var uri = GraalPyRuntimeResources.class.getProtectionDomain().getCodeSource().getLocation().toURI();
                    Path jarPath = Paths.get(uri);
                    if (Files.isRegularFile(jarPath)) {
                        runtimeHome = GraalPyRuntimeResources.prepare(jarPath, cacheDir);
                        if (runtimeHome.isPresent()) return runtimeHome;
                    }
                } catch (Throwable ignored) {}

                // 3. Fallback candidate paths
                Path[] candidateJars = new Path[] {
                    Paths.get("mods/kik-cc-python-aio-1.0.0.jar"),
                    Paths.get("mods/kik-cc-python-1.0.0.jar"),
                    Paths.get("/home/diff/data/WORK/Projects/kik-python-integration/dist/kik-cc-python-aio-1.0.0.jar"),
                    Paths.get("/home/diff/data/WORK/Projects/kik-python-integration/pyfish-fabric-26.2-1.2.7.jar"),
                    Paths.get("mods/pyfish-fabric-26.2-1.2.7.jar"),
                    Paths.get(System.getProperty("user.home"), "pyfish-fabric-26.2-1.2.7.jar")
                };
                for (Path jar : candidateJars) {
                    if (Files.exists(jar)) {
                        runtimeHome = GraalPyRuntimeResources.prepare(jar, cacheDir);
                        if (runtimeHome.isPresent()) break;
                    }
                }
                if (runtimeHome == null) runtimeHome = Optional.empty();
            } catch (Exception e) {
                runtimeHome = Optional.empty();
            }
        }
        return runtimeHome;
    }

    public static void warmup() {
        getRuntimeHome().ifPresent(home -> {
            Context.Builder builder = Context.newBuilder("python")
                .option("python.PosixModuleBackend", "java")
                .option("python.DontWriteBytecodeFlag", "true")
                .option("python.CheckHashPycsMode", "never")
                .allowAllAccess(true);
            home.configure(builder);
            try (Context ctx = builder.build()) {
                ctx.eval("python", "import sys, math, json, random, re");
            } catch (Throwable ignored) {}
        });
    }

    private void loadPythonModule(Context ctx, String modName, String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                String code = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String safeVar = "_mod_code_" + modName.replace('.', '_');
                ctx.getBindings("python").putMember(safeVar, code);
                ctx.eval("python",
                    "import sys, types\n" +
                    "_mod = types.ModuleType('" + modName + "')\n" +
                    "exec(" + safeVar + ", _mod.__dict__)\n" +
                    "sys.modules['" + modName + "'] = _mod\n" +
                    "del " + safeVar + "\n"
                );
            }
        } catch (Throwable ignored) {}
    }

    public void runCode(String code, String filename, List<String> argv, PythonExecutionBridge bridge) {
        Thread thread = new Thread(() -> {
            TerminalOutputStream termOut = new TerminalOutputStream(terminal);
            PrintStream out = new PrintStream(termOut, true);
            
            Context.Builder builder = Context.newBuilder("python")
                .option("python.PosixModuleBackend", "java")
                .option("python.DontWriteBytecodeFlag", "true")
                .option("python.CheckHashPycsMode", "never")
                .allowAllAccess(true)
                .out(out)
                .err(out);

            getRuntimeHome().ifPresent(home -> home.configure(builder));

            try (Context ctx = builder.build()) {
                Value bindings = ctx.getBindings("python");
                
                // Inject CC bridges
                bindings.putMember("_raw_turtle", new TurtleBridge(turtleApi, bridge));
                bindings.putMember("_raw_redstone", new RedstoneBridge(redstoneApi));
                bindings.putMember("_raw_term", new TermBridge(terminal));
                bindings.putMember("_raw_os", new OSBridge(env, osApi, bridge));
                bindings.putMember("_raw_fs", new FSBridge(fs));
                bindings.putMember("_raw_peripheral", new PeripheralBridge(system));
                bindings.putMember("_raw_colors", new ColorsBridge());
                bindings.putMember("_raw_kik", new KikBridge());

                // Setup bootstrap environment
                ctx.eval("python",
                    "import sys, types\n" +
                    "sys.modules['turtle'] = _raw_turtle\n" +
                    "sys.modules['redstone'] = _raw_redstone\n" +
                    "sys.modules['term'] = _raw_term\n" +
                    "sys.modules['colors'] = _raw_colors\n" +
                    "sys.modules['colours'] = _raw_colors\n" +
                    "sys.modules['kik'] = _raw_kik\n" +
                    "sys.modules['cc_fs'] = _raw_fs\n" +
                    "sys.modules['cc_os'] = _raw_os\n" +
                    "sys.modules['peripheral'] = _raw_peripheral\n" +
                    "cc = types.ModuleType('cc')\n" +
                    "cc.turtle = _raw_turtle\n" +
                    "cc.redstone = _raw_redstone\n" +
                    "cc.term = _raw_term\n" +
                    "cc.os = _raw_os\n" +
                    "cc.fs = _raw_fs\n" +
                    "cc.colors = _raw_colors\n" +
                    "cc.colours = _raw_colors\n" +
                    "cc.peripheral = _raw_peripheral\n" +
                    "cc.kik = _raw_kik\n" +
                    "sys.modules['cc'] = cc\n"
                );

                // Load and inject built-in beginner packages: requests, sqlite3, websocket, PIL
                loadPythonModule(ctx, "requests", "/python_modules/requests.py");
                loadPythonModule(ctx, "sqlite3", "/python_modules/sqlite3.py");
                loadPythonModule(ctx, "websocket", "/python_modules/websocket.py");
                loadPythonModule(ctx, "PIL", "/java_pillow.py");
                ctx.eval("python", "import sys; sys.modules['PIL.Image'] = sys.modules.get('PIL')\n");

                // Setup sys.argv
                Value sysModule = ctx.eval("python", "import sys; sys");
                Value argvVal = ctx.eval("python", "[]");
                argvVal.invokeMember("append", filename != null ? filename : "script.py");
                if (argv != null) {
                    for (String arg : argv) {
                        argvVal.invokeMember("append", arg);
                    }
                }
                sysModule.putMember("argv", argvVal);

                // Execute the Python 3 code
                ctx.eval("python", code);
                termOut.flush();
                bridge.signalFinished(true, null);
            } catch (PolyglotException e) {
                termOut.flush();
                if (e.isGuestException()) {
                    out.println("\033[31mPython Error: " + e.getMessage() + "\033[0m");
                } else if (!e.isCancelled()) {
                    out.println("\033[31mSystem Error: " + e.getMessage() + "\033[0m");
                }
                termOut.flush();
                bridge.signalFinished(false, e.getMessage());
            } catch (Throwable t) {
                termOut.flush();
                out.println("\033[31mUnexpected Error: " + t.getMessage() + "\033[0m");
                termOut.flush();
                bridge.signalFinished(false, t.getMessage());
            }
        }, "KiK-PythonWorker");
        bridge.setExecutionThread(thread);
        thread.start();
    }
}
