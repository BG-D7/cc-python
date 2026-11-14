package work.diffdev.ccpython.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import work.diffdev.ccpython.CCPythonMod;

@Mod(CCPythonMod.MOD_ID_NEOFORGE)
public class CCPythonNeoForgeMod {
    // FML requires exactly one public constructor.
    public CCPythonNeoForgeMod(IEventBus modEventBus) {
        CCPythonMod.init();
    }
}
