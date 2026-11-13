package work.diffdev.kik.python.bridge;

import dan200.computercraft.core.apis.IAPIEnvironment;
import dan200.computercraft.core.apis.OSAPI;
import dan200.computercraft.api.lua.LuaException;
import work.diffdev.kik.python.PythonExecutionBridge;
import java.util.Optional;

public class OSBridge {
    private final IAPIEnvironment env;
    private final OSAPI osApi;
    private final PythonExecutionBridge bridge;

    public OSBridge(IAPIEnvironment env, OSAPI osApi, PythonExecutionBridge bridge) {
        this.env = env;
        this.osApi = osApi;
        this.bridge = bridge;
    }

    public void sleep(double seconds) {
        if (osApi == null || bridge == null) {
            try { Thread.sleep((long)(seconds * 1000)); } catch (InterruptedException ignored) {}
            return;
        }
        try {
            int token = osApi.startTimer(seconds);
            while (true) {
                Object[] ev = bridge.yieldForEvent("timer");
                if (ev != null && ev.length > 1) {
                    if (ev[1] instanceof Number n && n.intValue() == token) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public int startTimer(double seconds) {
        if (osApi == null) return 0;
        try {
            return osApi.startTimer(seconds);
        } catch (LuaException e) {
            return 0;
        }
    }

    public void cancelTimer(int token) {
        if (osApi != null) osApi.cancelTimer(token);
    }

    public int getComputerID() {
        return env != null ? env.getComputerID() : 0;
    }

    public int computerID() {
        return getComputerID();
    }

    public String getComputerLabel() {
        return env != null ? env.getLabel() : null;
    }

    public String computerLabel() {
        return getComputerLabel();
    }

    public void setComputerLabel(String label) {
        if (env != null) env.setLabel(label);
    }

    public double clock() {
        return osApi != null ? osApi.clock() : (System.currentTimeMillis() / 1000.0);
    }

    public double time() {
        return osApi != null ? osApi.clock() : 0.0;
    }

    public int day() {
        if (osApi == null) return 1;
        try {
            return osApi.day(Optional.empty());
        } catch (LuaException e) {
            return 1;
        }
    }

    public long epoch() {
        if (osApi == null) return System.currentTimeMillis();
        try {
            return osApi.epoch(Optional.empty());
        } catch (LuaException e) {
            return System.currentTimeMillis();
        }
    }

    public void queueEvent(String name, Object... args) {
        if (env != null) env.queueEvent(name, args);
    }

    public Object[] pullEvent(String filter) {
        if (bridge == null) return new Object[0];
        try {
            return bridge.yieldForEvent(filter);
        } catch (InterruptedException e) {
            return new Object[] { "terminate" };
        }
    }
}
