package work.diffdev.ccpython.fabric;

import net.fabricmc.api.ModInitializer;
import work.diffdev.ccpython.CCPythonMod;

public class CCPythonFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        CCPythonMod.init();
    }
}
