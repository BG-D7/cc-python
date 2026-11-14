package work.diffdev.kik.python.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import work.diffdev.kik.python.KikCCPythonMod;

@Mod(KikCCPythonMod.MOD_ID_NEOFORGE)
public class KikCCPythonNeoForgeMod {
    // FML requires exactly one public constructor.
    public KikCCPythonNeoForgeMod(IEventBus modEventBus) {
        KikCCPythonMod.init();
    }
}
