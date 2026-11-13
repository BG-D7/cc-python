package work.diffdev.kik.python;

import dan200.computercraft.core.terminal.Terminal;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class TerminalOutputStream extends OutputStream {
    private final Terminal terminal;
    private final StringBuilder lineBuffer = new StringBuilder();
    private boolean inAnsi = false;
    private final StringBuilder ansiBuffer = new StringBuilder();

    public TerminalOutputStream(Terminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public synchronized void write(int b) {
        writeChar((char) (b & 0xFF));
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        String s = new String(b, off, len, StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) {
            writeChar(s.charAt(i));
        }
    }

    private void writeChar(char c) {
        if (inAnsi) {
            ansiBuffer.append(c);
            if (Character.isLetter(c) || c == 'm' || c == 'H' || c == 'J') {
                inAnsi = false;
                processAnsi(ansiBuffer.toString());
                ansiBuffer.setLength(0);
            }
            return;
        }

        if (c == '\033') {
            flushBuffer();
            inAnsi = true;
            ansiBuffer.setLength(0);
            return;
        }

        if (terminal == null) return;

        if (c == '\r') {
            flushBuffer();
            terminal.setCursorPos(0, terminal.getCursorY());
            return;
        }

        if (c == '\n') {
            flushBuffer();
            int y = terminal.getCursorY();
            int h = terminal.getHeight();
            if (y >= h - 1) {
                terminal.scroll(1);
                terminal.setCursorPos(0, h - 1);
            } else {
                terminal.setCursorPos(0, y + 1);
            }
            return;
        }

        lineBuffer.append(translateToCP1251(c));
        int w = terminal.getWidth();
        if (terminal.getCursorX() + lineBuffer.length() >= w) {
            flushBuffer();
            int y = terminal.getCursorY();
            int h = terminal.getHeight();
            if (y >= h - 1) {
                terminal.scroll(1);
                terminal.setCursorPos(0, h - 1);
            } else {
                terminal.setCursorPos(0, y + 1);
            }
        }
    }

    private void processAnsi(String code) {
        if (terminal == null) return;
        if (code.equals("[0m")) {
            terminal.setTextColour(0); // White
        } else if (code.equals("[31m") || code.equals("[91m")) {
            terminal.setTextColour(14); // Red
        } else if (code.equals("[32m") || code.equals("[92m")) {
            terminal.setTextColour(13); // Green
        } else if (code.equals("[33m") || code.equals("[93m")) {
            terminal.setTextColour(4);  // Yellow
        } else if (code.equals("[34m") || code.equals("[94m")) {
            terminal.setTextColour(11); // Blue
        } else if (code.equals("[35m") || code.equals("[95m")) {
            terminal.setTextColour(10); // Purple
        } else if (code.equals("[36m") || code.equals("[96m")) {
            terminal.setTextColour(9);  // Cyan
        }
    }

    private char translateToCP1251(char code) {
        if (code < 0x80) return code;
        if (code >= 0x0410 && code <= 0x044F) {
            return (char) (code - 0x0410 + 0xC0);
        }
        return switch (code) {
            case 0x0401 -> (char) 0xA8; // Ё
            case 0x0451 -> (char) 0xB8; // ё
            case 0x2116 -> (char) 0xB9; // №
            case 0x00AB -> (char) 0xAB; // «
            case 0x00BB -> (char) 0xBB; // »
            case 0x2014, 0x2013 -> '-';  // — –
            case 0x2026 -> '.';          // …
            case 0x201C, 0x201D, 0x201E -> '"';
            default -> '?';
        };
    }

    @Override
    public synchronized void flush() {
        flushBuffer();
    }

    private void flushBuffer() {
        if (terminal != null && !lineBuffer.isEmpty()) {
            String text = lineBuffer.toString();
            terminal.write(text);
            terminal.setCursorPos(terminal.getCursorX() + text.length(), terminal.getCursorY());
            lineBuffer.setLength(0);
        }
    }
}
