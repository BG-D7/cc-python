package work.diffdev.kik.python.bridge;

import dan200.computercraft.core.terminal.Terminal;

public class TermBridge {
    private final Terminal terminal;

    public TermBridge(Terminal terminal) {
        this.terminal = terminal;
    }

    public void write(String text) {
        if (terminal != null && text != null) {
            terminal.write(text);
            terminal.setCursorPos(terminal.getCursorX() + text.length(), terminal.getCursorY());
        }
    }

    public void clear() {
        if (terminal != null) terminal.clear();
    }

    public void clearLine() {
        if (terminal != null) terminal.clearLine();
    }

    public int[] getCursorPos() {
        if (terminal == null) return new int[] { 1, 1 };
        return new int[] { terminal.getCursorX() + 1, terminal.getCursorY() + 1 };
    }

    public void setCursorPos(int x, int y) {
        if (terminal != null) terminal.setCursorPos(x - 1, y - 1);
    }

    public void setCursorBlink(boolean blink) {
        if (terminal != null) terminal.setCursorBlink(blink);
    }

    public boolean isColor() {
        return terminal != null && terminal.isColour();
    }

    public boolean isColour() {
        return isColor();
    }

    public int[] getSize() {
        if (terminal == null) return new int[] { 51, 19 };
        return new int[] { terminal.getWidth(), terminal.getHeight() };
    }

    public void scroll(int n) {
        if (terminal != null) terminal.scroll(n);
    }

    public void setTextColor(int color) {
        if (terminal != null && color > 0) {
            int index = Integer.numberOfTrailingZeros(color);
            if (index >= 0 && index < 16) {
                terminal.setTextColour(index);
            }
        }
    }

    public void setTextColour(int color) {
        setTextColor(color);
    }

    public void setBackgroundColor(int color) {
        if (terminal != null && color > 0) {
            int index = Integer.numberOfTrailingZeros(color);
            if (index >= 0 && index < 16) {
                terminal.setBackgroundColour(index);
            }
        }
    }

    public void setBackgroundColour(int color) {
        setBackgroundColor(color);
    }

    public int getTextColor() {
        if (terminal == null) return 1;
        return 1 << terminal.getTextColour();
    }

    public int getTextColour() {
        return getTextColor();
    }

    public int getBackgroundColor() {
        if (terminal == null) return 32768;
        return 1 << terminal.getBackgroundColour();
    }

    public int getBackgroundColour() {
        return getBackgroundColor();
    }
}
