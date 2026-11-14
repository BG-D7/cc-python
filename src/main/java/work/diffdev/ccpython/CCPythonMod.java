package work.diffdev.ccpython;

import dan200.computercraft.api.ComputerCraftAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

public class CCPythonMod {
    public static final String MOD_ID = "ccpython";
    public static final String MOD_ID_NEOFORGE = "ccpython";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) return;

        PythonConfig config = PythonConfig.get();
        try {
            Files.createDirectories(PythonConfig.DIR.resolve("libs"));
        } catch (Exception e) {
            LOGGER.warn("[CC: Python] Cannot create {}: {}", PythonConfig.DIR.resolve("libs"), e.toString());
        }
        if (config.sandboxDisabled) {
            LOGGER.warn("[CC: Python] sandbox.disable = true: Python programs have full access to this server.");
        }

        ComputerCraftAPI.registerAPIFactory(PythonAPI::create);

        if (config.prewarm) {
            // Unpack the runtime and build a ready interpreter in the background, so the first program starts fast.
            PythonRuntime.SCHEDULER.execute(() -> {
                long t0 = System.nanoTime();
                if (PythonRuntime.home().isEmpty()) return;
                LOGGER.info("[CC: Python] Python runtime ready in {} ms", (System.nanoTime() - t0) / 1_000_000);
                PythonRuntime.prewarmAsync(!config.sandboxDisabled);
            });
        }
        LOGGER.info("[CC: Python] Python 3 (GraalPy) for CC: Tweaked registered.");
    }
}
