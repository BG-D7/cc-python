package work.diffdev.kik.python;

import net.fabricmc.api.ModInitializer;
import dan200.computercraft.api.ComputerCraftAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class KikCCPythonMod implements ModInitializer {
    public static final String MOD_ID = "kik-cc-python";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
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
