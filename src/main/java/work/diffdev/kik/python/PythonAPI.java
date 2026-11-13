package work.diffdev.kik.python;

import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.component.ComputerComponents;
import dan200.computercraft.core.apis.IAPIEnvironment;
import dan200.computercraft.core.apis.OSAPI;
import dan200.computercraft.core.apis.RedstoneAPI;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.turtle.apis.TurtleAPI;
import dan200.computercraft.shared.turtle.core.TurtleAccessInternal;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.filesystem.FileSystem;
import dan200.computercraft.core.filesystem.FileSystemWrapper;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public class PythonAPI implements ILuaAPI {
    private final IComputerSystem system;
    private IAPIEnvironment env;
    private TurtleAPI turtleApi;
    private RedstoneAPI redstoneApi;
    private OSAPI osApi;

    public PythonAPI(IComputerSystem system) {
        this.system = system;
        resolveEnvironment();
    }

    public static PythonAPI create(IComputerSystem system) {
        return new PythonAPI(system);
    }

    private void resolveEnvironment() {
        if (env == null && system != null) {
            try {
                Field envField = system.getClass().getDeclaredField("environment");
                envField.setAccessible(true);
                env = (IAPIEnvironment) envField.get(system);
            } catch (Throwable ignored) {}
        }
    }

    private Terminal getTerminal() {
        resolveEnvironment();
        return env != null ? env.getTerminal() : null;
    }

    private FileSystem getFileSystem() {
        resolveEnvironment();
        return env != null ? env.getFileSystem() : null;
    }

    private TurtleAPI getTurtleAPI() {
        if (turtleApi == null && system != null) {
            try {
                var turtle = system.getComponent(ComputerComponents.TURTLE);
                var metrics = system.getComponent(ServerComputer.METRICS);
                if (turtle instanceof TurtleAccessInternal internalTurtle && metrics != null) {
                    turtleApi = new TurtleAPI(metrics, internalTurtle);
                }
            } catch (Throwable ignored) {}
        }
        return turtleApi;
    }

    private RedstoneAPI getRedstoneAPI() {
        if (redstoneApi == null && system != null) {
            try {
                Field compField = system.getClass().getDeclaredField("computer");
                compField.setAccessible(true);
                Object srvComp = compField.get(system);
                Field coreCompField = srvComp.getClass().getDeclaredField("computer");
                coreCompField.setAccessible(true);
                Computer coreComp = (Computer) coreCompField.get(srvComp);
                if (coreComp != null) {
                    redstoneApi = new RedstoneAPI(coreComp.getRedstone());
                }
            } catch (Throwable ignored) {}
        }
        return redstoneApi;
    }

    private OSAPI getOSAPI() {
        if (osApi == null) {
            resolveEnvironment();
            if (env != null) {
                osApi = new OSAPI(env);
            }
        }
        return osApi;
    }

    @Override
    public String[] getNames() {
        return new String[] { "python", "py" };
    }

    @LuaFunction
    public final String version() {
        return "Python 3.12.8 (GraalPy on CC: Tweaked)";
    }

    @LuaFunction
    public final MethodResult execute(String code, Optional<List<String>> args) {
        Terminal term = getTerminal();
        FileSystem fs = getFileSystem();
        PythonSession session = new PythonSession(system, env, term, fs, getTurtleAPI(), getRedstoneAPI(), getOSAPI());
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        session.runCode(code, "inline.py", args.orElse(List.of()), bridge);
        return bridge.handleInitialStep();
    }

    @LuaFunction
    public final MethodResult run(String path, Optional<List<String>> args) {
        FileSystem fs = getFileSystem();
        if (fs == null) {
            return MethodResult.of(false, "FileSystem not available");
        }
        String code;
        try (FileSystemWrapper<SeekableByteChannel> wrapper = fs.openForRead(path)) {
            SeekableByteChannel ch = wrapper.get();
            ByteBuffer buf = ByteBuffer.allocate((int) ch.size());
            ch.read(buf);
            code = new String(buf.array(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return MethodResult.of(false, "Could not read file: " + e.getMessage());
        }

        Terminal term = getTerminal();
        PythonSession session = new PythonSession(system, env, term, fs, getTurtleAPI(), getRedstoneAPI(), getOSAPI());
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        session.runCode(code, path, args.orElse(List.of()), bridge);
        return bridge.handleInitialStep();
    }

    @LuaFunction
    public final MethodResult eval(String expr) {
        String wrapped = "print(" + expr + ")";
        return execute(wrapped, Optional.empty());
    }
}
