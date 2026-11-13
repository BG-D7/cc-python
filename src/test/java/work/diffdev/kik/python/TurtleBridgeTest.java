package work.diffdev.kik.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import work.diffdev.kik.python.bridge.TurtleBridge;

import static org.junit.jupiter.api.Assertions.*;

public class TurtleBridgeTest {

    @Test
    @DisplayName("TurtleBridge handles null api gracefully")
    void testNullApiSafety() {
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        TurtleBridge turtle = new TurtleBridge(null, bridge);

        assertFalse(turtle.forward());
        assertFalse(turtle.back());
        assertFalse(turtle.up());
        assertFalse(turtle.down());
        assertFalse(turtle.turnLeft());
        assertFalse(turtle.turnRight());
        assertFalse(turtle.dig());
        assertFalse(turtle.place());
        assertFalse(turtle.detect());
        assertEquals(0, turtle.getFuelLevel());
        assertEquals(1, turtle.getSelectedSlot());
        assertEquals(0, turtle.getItemCount());
    }
}
