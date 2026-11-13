package work.diffdev.kik.python.bridge;

public class KikBridge {
    public static String ru(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                sb.append(c);
            } else if (c >= 0x0410 && c <= 0x044F) {
                sb.append((char) (c - 0x0410 + 0xC0));
            } else {
                switch (c) {
                    case 0x0401 -> sb.append((char) 0xA8); // Ё
                    case 0x0451 -> sb.append((char) 0xB8); // ё
                    case 0x2116 -> sb.append((char) 0xB9); // №
                    case 0x00AB -> sb.append((char) 0xAB); // «
                    case 0x00BB -> sb.append((char) 0xBB); // »
                    case 0x2014, 0x2013 -> sb.append('-');
                    case 0x2026 -> sb.append('.');
                    case 0x201C, 0x201D, 0x201E -> sb.append('"');
                    default -> sb.append('?');
                }
            }
        }
        return sb.toString();
    }
}
