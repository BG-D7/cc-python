package work.diffdev.kik.python;

import dan200.computercraft.api.ComputerCraftAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

public class KikCCPythonMod {
    public static final String MOD_ID = "kik-cc-python";
    public static final String MOD_ID_NEOFORGE = "kik_cc_python";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("[KiK CC-Python AIO] Initializing modern Python 3 (GraalPy) for CC: Tweaked...");

        // Ensure safe tmpdir for native libraries (fixes noexec /tmp on Linux/NixOS)
        try {
            Path cacheTmp = Paths.get(System.getProperty("user.home"), ".kik-graalpy-cache", "tmp");
            Files.createDirectories(cacheTmp);
            System.setProperty("org.sqlite.tmpdir", cacheTmp.toString());
        } catch (Throwable ignored) {}

        System.setProperty("polyglotimpl.DisableMultiReleaseCheck", "true");

        ComputerCraftAPI.registerAPIFactory(PythonAPI::create);

        // Pre-warm GraalPy runtime cache asynchronously in background
        Thread warmupThread = new Thread(() -> {
            try {
                LOGGER.info("[KiK CC-Python AIO] Pre-warming GraalPy runtime resources...");
                PythonSession.warmup();
                LOGGER.info("[KiK CC-Python AIO] GraalPy runtime ready.");
            } catch (Throwable t) {
                LOGGER.warn("[KiK CC-Python AIO] Pre-warm note: {}", t.getMessage());
            }
        }, "KiK-GraalPy-Warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();

        LOGGER.info("[KiK CC-Python AIO] Initialization complete.");
    }
}
