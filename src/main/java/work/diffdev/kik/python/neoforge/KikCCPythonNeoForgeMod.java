package work.diffdev.kik.python.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import work.diffdev.kik.python.KikCCPythonMod;

@Mod(KikCCPythonMod.MOD_ID_NEOFORGE)
public class KikCCPythonNeoForgeMod {

    public KikCCPythonNeoForgeMod() {
        KikCCPythonMod.init();
    }

    public KikCCPythonNeoForgeMod(IEventBus modEventBus) {
        KikCCPythonMod.init();
    }
}
