package work.diffdev.kik.python.bridge;

import dan200.computercraft.core.apis.RedstoneAPI;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.api.lua.LuaException;
import java.util.List;

public class RedstoneBridge {
    private final RedstoneAPI api;

    public RedstoneBridge(RedstoneAPI api) {
        this.api = api;
    }

    private ComputerSide parseSide(String side) {
        if (side == null) return ComputerSide.FRONT;
        ComputerSide s = ComputerSide.valueOfInsensitive(side);
        return s != null ? s : ComputerSide.FRONT;
    }

    public List<String> getSides() {
        return ComputerSide.NAMES;
    }

    public void setOutput(String side, boolean value) {
        if (api != null) api.setOutput(parseSide(side), value);
    }

    public boolean getOutput(String side) {
        return api != null && api.getOutput(parseSide(side));
    }

    public boolean getInput(String side) {
        return api != null && api.getInput(parseSide(side));
    }

    public void setAnalogOutput(String side, int strength) {
        if (api != null) {
            try {
                api.setAnalogOutput(parseSide(side), strength);
            } catch (LuaException ignored) {}
        }
    }

    public int getAnalogOutput(String side) {
        return api != null ? api.getAnalogOutput(parseSide(side)) : 0;
    }

    public int getAnalogInput(String side) {
        return api != null ? api.getAnalogInput(parseSide(side)) : 0;
    }
}
