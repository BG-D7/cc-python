package work.diffdev.ccpython;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Conversions between Python text (Unicode) and Lua strings (byte strings; CC hands them to Java one byte per char).
 * Incoming bytes are UTF-8 when they decode as such and otherwise the configured font encoding (cp1251 for kik-pack),
 * so both a UTF-8 file and Cyrillic typed into the terminal arrive as proper text.
 */
public final class TextCodec {
    private TextCodec() {}

    /** Raw bytes of a string CC produced from a Lua value. */
    public static byte[] luaBytes(String luaString) {
        return luaString.getBytes(StandardCharsets.ISO_8859_1);
    }

    public static String decode(byte[] bytes, Charset fallback) {
        boolean ascii = true;
        for (byte b : bytes) {
            if (b < 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) return new String(bytes, StandardCharsets.US_ASCII);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, fallback);
        }
    }

    public static String decodeLua(String luaString, Charset fallback) {
        return decode(luaBytes(luaString), fallback);
    }

    /** Encode text for Lua/the terminal font; characters the font lacks get a close ASCII stand-in or '?'. */
    public static byte[] encode(String text, Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8)) return text.getBytes(StandardCharsets.UTF_8);
        CharsetEncoder enc = charset.newEncoder();
        ByteBuffer out = ByteBuffer.allocate(text.length() * 2 + 8);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            String piece = Character.toString(cp);
            byte[] bytes;
            if (cp < 0x80) {
                bytes = new byte[] { (byte) cp };
            } else if (enc.canEncode(piece)) {
                bytes = piece.getBytes(charset);
            } else {
                bytes = fallback(cp).getBytes(StandardCharsets.US_ASCII);
            }
            if (out.remaining() < bytes.length) {
                ByteBuffer bigger = ByteBuffer.allocate(out.capacity() * 2 + bytes.length);
                out.flip();
                bigger.put(out);
                out = bigger;
            }
            out.put(bytes);
        }
        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }

    /** Lua string (one char per byte) for the given text. */
    public static String encodeLua(String text, Charset charset) {
        return new String(encode(text, charset), StandardCharsets.ISO_8859_1);
    }

    private static String fallback(int cp) {
        return switch (cp) {
            case 0x2014, 0x2013, 0x2212 -> "-";
            case 0x2026 -> "...";
            case 0x201C, 0x201D, 0x201E, 0x00AB, 0x00BB -> "\"";
            case 0x2018, 0x2019, 0x201A -> "'";
            case 0x00A0 -> " ";
            case 0x2116 -> "No";
            default -> "?";
        };
    }
}
