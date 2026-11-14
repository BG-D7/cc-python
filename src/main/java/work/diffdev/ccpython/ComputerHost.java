package work.diffdev.ccpython;

import dan200.computercraft.core.filesystem.FileSystem;


/** The parts of a CC computer a Python run needs; lets tests drive runs without a Minecraft server. */
public interface ComputerHost {
    int id();

    /** The computer's filesystem, or null if it is not available (the script then sees an empty disk). */
    FileSystem fileSystem();

    /** Thread-safe: queue an event on the computer, waking its Lua side. */
    void queueEvent(String name, Object... args);
}
