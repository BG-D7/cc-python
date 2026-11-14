package work.diffdev.kik.python;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The context-level stdout/stderr stream (used by GraalPy internals; scripts print through sys.stdout, which goes
 * straight to the run). Decodes UTF-8 incrementally, so a character split across two writes survives.
 */
public final class SwitchableOutput extends OutputStream {
    private volatile Consumer<String> target;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final ByteBuffer pending = ByteBuffer.allocate(8192);

    public void bind(Consumer<String> target) {
        synchronized (this) {
            pending.clear();
            decoder.reset();
        }
        this.target = target;
    }

    @Override
    public void write(int b) {
        write(new byte[] { (byte) b }, 0, 1);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        Consumer<String> t = target;
        if (t == null) return;
        while (len > 0) {
            int n = Math.min(len, pending.remaining());
            pending.put(b, off, n);
            off += n;
            len -= n;
            pending.flip();
            CharBuffer out = CharBuffer.allocate(pending.remaining() + 1);
            decoder.decode(pending, out, false);
            pending.compact();
            out.flip();
            if (out.hasRemaining()) t.accept(out.toString());
        }
    }
}
