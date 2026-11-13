package work.diffdev.kik.python.bridge;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.lua.IComputerSystem;
import java.util.*;

public class PeripheralBridge {
    private final IComputerSystem system;

    public PeripheralBridge(IComputerSystem system) {
        this.system = system;
    }

    public List<String> getNames() {
        if (system == null) return Collections.emptyList();
        return new ArrayList<>(system.getAvailablePeripherals().keySet());
    }

    public boolean isPresent(String name) {
        if (system == null) return false;
        return system.getAvailablePeripheral(name) != null;
    }

    public String getType(String name) {
        if (system == null) return null;
        IPeripheral p = system.getAvailablePeripheral(name);
        return p != null ? p.getType() : null;
    }
}
