package work.diffdev.ccpython;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Turns script output into drawing operations for the Lua driver: text runs plus colour/cursor changes taken from
 * ANSI escapes. The driver draws them with CC's own write(), so wrapping, scrolling and term.redirect behave exactly
 * like a Lua program's output. Operations are flat pairs: kind, argument.
 */
final class OutputOps {
    private final Charset charset;
    private final boolean stderr;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder escape = new StringBuilder();
    private boolean inEscape;

    OutputOps(Charset charset, boolean stderr) {
        this.charset = charset;
        this.stderr = stderr;
    }

    /** Parse {@code s}, appending complete operations to {@code ops}. */
    void feed(String s, List<Object> ops) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inEscape) {
                escape.append(c);
                if (escape.length() == 1 && c != '[') {
                    inEscape = false; // not a CSI sequence; drop it
                } else if (escape.length() > 1 && c >= 0x40 && c <= 0x7e) {
                    inEscape = false;
                    flushText(ops);
                    csi(escape.substring(1, escape.length() - 1), c, ops);
                } else if (escape.length() > 32) {
                    inEscape = false;
                }
                continue;
            }
            switch (c) {
                case '\033' -> {
                    inEscape = true;
                    escape.setLength(0);
                }
                case '\r' -> {
                    flushText(ops);
                    ops.add("cr");
                    ops.add(0);
                }
                case '\t' -> text.append("    ");
                case '\b', '\u0007', '\u0000' -> {}
                default -> text.append(c);
            }
        }
        flushText(ops);
    }

    private void flushText(List<Object> ops) {
        if (text.isEmpty()) return;
        ops.add(stderr ? "e" : "t");
        ops.add(TextCodec.encode(text.toString(), charset));
        text.setLength(0);
    }

    private void csi(String params, char cmd, List<Object> ops) {
        switch (cmd) {
            case 'm' -> {
                if (params.isEmpty()) params = "0";
                for (String p : params.split(";")) {
                    int code;
                    try {
                        code = Integer.parseInt(p);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (code == 0) {
                        ops.add("fg");
                        ops.add(0);
                        ops.add("bg");
                        ops.add(0);
                    } else if (code == 39) {
                        ops.add("fg");
                        ops.add(0);
                    } else if (code == 49) {
                        ops.add("bg");
                        ops.add(0);
                    } else if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) {
                        ops.add("fg");
                        ops.add(colour(code));
                    } else if ((code >= 40 && code <= 47) || (code >= 100 && code <= 107)) {
                        ops.add("bg");
                        ops.add(colour(code - 10));
                    }
                }
            }
            case 'J' -> {
                if (params.equals("2") || params.equals("3")) {
                    ops.add("cls");
                    ops.add(0);
                }
            }
            case 'K' -> {
                ops.add("el");
                ops.add(0);
            }
            case 'H', 'f' -> {
                int row = 1, col = 1;
                String[] rc = params.split(";");
                try {
                    if (rc.length > 0 && !rc[0].isEmpty()) row = Integer.parseInt(rc[0]);
                    if (rc.length > 1 && !rc[1].isEmpty()) col = Integer.parseInt(rc[1]);
                } catch (NumberFormatException ignored) {}
                ops.add("pos");
                ops.add(List.of(col, row));
            }
            default -> {}
        }
    }

    /** CC colour bit for an ANSI foreground code. */
    private static int colour(int code) {
        return switch (code) {
            case 30 -> 32768; // black
            case 31 -> 16384; // red
            case 32 -> 8192;  // green
            case 33 -> 16;    // yellow
            case 34 -> 2048;  // blue
            case 35 -> 4;     // magenta
            case 36 -> 512;   // cyan
            case 37 -> 256;   // light grey
            case 90 -> 128;   // grey
            case 91 -> 16384; // red
            case 92 -> 32;    // lime
            case 93 -> 16;    // yellow
            case 94 -> 8;     // light blue
            case 95 -> 1024;  // purple
            case 96 -> 512;   // cyan
            default -> 1;     // white
        };
    }
}
