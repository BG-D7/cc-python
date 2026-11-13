package work.diffdev.kik.python.fabric;

import net.fabricmc.api.ModInitializer;
import work.diffdev.kik.python.KikCCPythonMod;

public class KikCCPythonFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        KikCCPythonMod.init();
    }
}
