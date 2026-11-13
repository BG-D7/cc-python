package work.diffdev.kik.python.bridge;

import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.shared.turtle.apis.TurtleAPI;
import work.diffdev.kik.python.PythonExecutionBridge;

import java.util.Optional;

public class TurtleBridge {
    private final TurtleAPI api;
    private final PythonExecutionBridge bridge;

    public TurtleBridge(TurtleAPI api, PythonExecutionBridge bridge) {
        this.api = api;
        this.bridge = bridge;
    }

    private Object[] executeAsync(MethodResult result) {
        if (result == null) return new Object[] { false, "Turtle not available" };
        while (result.getCallback() != null) {
            try {
                Object[] eventArgs = bridge.yieldForEvent("turtle_response");
                result = result.getCallback().resume(eventArgs);
                if (result == null) return new Object[] { false };
            } catch (Exception e) {
                return new Object[] { false, e.getMessage() };
            }
        }
        return result.getResult();
    }

    private boolean checkBool(Object[] res) {
        return res != null && res.length > 0 && Boolean.TRUE.equals(res[0]);
    }

    public boolean forward() {
        if (api == null) return false;
        return checkBool(executeAsync(api.forward()));
    }

    public boolean back() {
        if (api == null) return false;
        return checkBool(executeAsync(api.back()));
    }

    public boolean up() {
        if (api == null) return false;
        return checkBool(executeAsync(api.up()));
    }

    public boolean down() {
        if (api == null) return false;
        return checkBool(executeAsync(api.down()));
    }

    public boolean turnLeft() {
        if (api == null) return false;
        return checkBool(executeAsync(api.turnLeft()));
    }

    public boolean turnRight() {
        if (api == null) return false;
        return checkBool(executeAsync(api.turnRight()));
    }

    public boolean dig() {
        if (api == null) return false;
        return checkBool(executeAsync(api.dig(Optional.empty())));
    }

    public boolean digUp() {
        if (api == null) return false;
        return checkBool(executeAsync(api.digUp(Optional.empty())));
    }

    public boolean digDown() {
        if (api == null) return false;
        return checkBool(executeAsync(api.digDown(Optional.empty())));
    }

    public boolean place() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.place(new ObjectArguments())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean placeUp() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.placeUp(new ObjectArguments())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean placeDown() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.placeDown(new ObjectArguments())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean detect() {
        if (api == null) return false;
        return checkBool(executeAsync(api.detect()));
    }

    public boolean detectUp() {
        if (api == null) return false;
        return checkBool(executeAsync(api.detectUp()));
    }

    public boolean detectDown() {
        if (api == null) return false;
        return checkBool(executeAsync(api.detectDown()));
    }

    public Object[] inspect() {
        if (api == null) return new Object[] { false, "No turtle" };
        return executeAsync(api.inspect());
    }

    public Object[] inspectUp() {
        if (api == null) return new Object[] { false, "No turtle" };
        return executeAsync(api.inspectUp());
    }

    public Object[] inspectDown() {
        if (api == null) return new Object[] { false, "No turtle" };
        return executeAsync(api.inspectDown());
    }

    public boolean compare() {
        if (api == null) return false;
        return checkBool(executeAsync(api.compare()));
    }

    public boolean compareUp() {
        if (api == null) return false;
        return checkBool(executeAsync(api.compareUp()));
    }

    public boolean compareDown() {
        if (api == null) return false;
        return checkBool(executeAsync(api.compareDown()));
    }

    public boolean compareTo(int slot) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.compareTo(slot)));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean select(int slot) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.select(slot)));
        } catch (LuaException e) {
            return false;
        }
    }

    public int getSelectedSlot() {
        if (api == null) return 1;
        return api.getSelectedSlot();
    }

    public int getItemCount(int slot) {
        if (api == null) return 0;
        try {
            return api.getItemCount(Optional.of(slot));
        } catch (LuaException e) {
            return 0;
        }
    }

    public int getItemCount() {
        if (api == null) return 0;
        try {
            return api.getItemCount(Optional.empty());
        } catch (LuaException e) {
            return 0;
        }
    }

    public int getItemSpace(int slot) {
        if (api == null) return 0;
        try {
            return api.getItemSpace(Optional.of(slot));
        } catch (LuaException e) {
            return 0;
        }
    }

    public int getItemSpace() {
        if (api == null) return 0;
        try {
            return api.getItemSpace(Optional.empty());
        } catch (LuaException e) {
            return 0;
        }
    }

    public Object getItemDetail(int slot) {
        if (api == null) return null;
        try {
            MethodResult res = api.getItemDetail(null, Optional.of(slot), Optional.of(false));
            Object[] r = res.getResult();
            return r != null && r.length > 0 ? r[0] : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Object getItemDetail() {
        if (api == null) return null;
        try {
            MethodResult res = api.getItemDetail(null, Optional.empty(), Optional.of(false));
            Object[] r = res.getResult();
            return r != null && r.length > 0 ? r[0] : null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean attack() {
        if (api == null) return false;
        return checkBool(executeAsync(api.attack(Optional.empty())));
    }

    public boolean attackUp() {
        if (api == null) return false;
        return checkBool(executeAsync(api.attackUp(Optional.empty())));
    }

    public boolean attackDown() {
        if (api == null) return false;
        return checkBool(executeAsync(api.attackDown(Optional.empty())));
    }

    public boolean suck(int amount) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.suck(Optional.of(amount))));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean suck() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.suck(Optional.empty())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean suckUp(int amount) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.suckUp(Optional.of(amount))));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean suckUp() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.suckUp(Optional.empty())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean suckDown(int amount) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.suckDown(Optional.of(amount))));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean suckDown() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.suckDown(Optional.empty())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean drop(int amount) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.drop(Optional.of(amount))));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean drop() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.drop(Optional.empty())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean dropUp(int amount) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.dropUp(Optional.of(amount))));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean dropUp() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.dropUp(Optional.empty())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean dropDown(int amount) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.dropDown(Optional.of(amount))));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean dropDown() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.dropDown(Optional.empty())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean transferTo(int slot, int count) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.transferTo(slot, Optional.of(count))));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean transferTo(int slot) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.transferTo(slot, Optional.empty())));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean equipLeft() {
        if (api == null) return false;
        return checkBool(executeAsync(api.equipLeft()));
    }

    public boolean equipRight() {
        if (api == null) return false;
        return checkBool(executeAsync(api.equipRight()));
    }

    public int getFuelLevel() {
        if (api == null) return 0;
        Object res = api.getFuelLevel();
        if (res instanceof Number n) return n.intValue();
        return 0;
    }

    public int getFuelLimit() {
        if (api == null) return 0;
        Object res = api.getFuelLimit();
        if (res instanceof Number n) return n.intValue();
        return 0;
    }

    public boolean refuel(int amount) {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.refuel(Optional.of(amount))));
        } catch (LuaException e) {
            return false;
        }
    }

    public boolean refuel() {
        if (api == null) return false;
        try {
            return checkBool(executeAsync(api.refuel(Optional.empty())));
        } catch (LuaException e) {
            return false;
        }
    }
}
