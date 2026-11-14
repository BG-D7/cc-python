package work.diffdev.kik.python.net;

import org.graalvm.polyglot.HostAccess;
import work.diffdev.kik.python.PyRun;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A websocket for Python's websocket module, checked against CC's rules and message size limit. */
public final class WebSocketBridge implements AutoCloseable {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private final PyRun run;
    private final int maxMessage;
    private final LinkedBlockingQueue<Object[]> incoming = new LinkedBlockingQueue<>();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private final String error;

    private WebSocketBridge(PyRun run, int maxMessage, String error) {
        this.run = run;
        this.maxMessage = maxMessage;
        this.error = error;
    }

    public static WebSocketBridge connect(PyRun run, String url, Map<String, String> headers, double timeout) {
        NetRules.Checked checked;
        try {
            checked = NetRules.check(url, true);
        } catch (NetRules.Denied e) {
            return new WebSocketBridge(run, 0, e.getMessage());
        }
        WebSocketBridge ws = new WebSocketBridge(run, checked.options().websocketMessage(), null);
        WebSocket.Builder b = CLIENT.newWebSocketBuilder();
        if (timeout > 0) b.connectTimeout(Duration.ofMillis((long) (timeout * 1000)));
        headers.forEach((k, v) -> {
            try {
                b.header(k, v);
            } catch (IllegalArgumentException ignored) {
                // Restricted handshake header.
            }
        });
        CompletableFuture<WebSocket> f = b.buildAsync(checked.uri(), ws.new Listener());
        try {
            ws.socket = run.await(f, timeout > 0 ? (long) (timeout * 1000) : 30_000);
        } catch (TimeoutException e) {
            return new WebSocketBridge(run, 0, "Timed out");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return new WebSocketBridge(run, 0, c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
        }
        run.track(ws);
        return ws;
    }

    /** Null if connected, else why the connection failed. */
    @HostAccess.Export
    public String error() {
        return error;
    }

    /** Send text (or bytes given as latin-1 when binary). Returns null or an error message. */
    @HostAccess.Export
    public String send(String data, boolean binary) {
        WebSocket s = socket;
        if (s == null || closed) return "Websocket is closed";
        byte[] bytes = binary ? data.getBytes(StandardCharsets.ISO_8859_1) : data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxMessage) return "Message is too large";
        try {
            run.await(binary ? s.sendBinary(ByteBuffer.wrap(bytes), true) : s.sendText(data, true), 30_000);
            return null;
        } catch (TimeoutException e) {
            return "Timed out";
        } catch (ExecutionException e) {
            return e.getCause() == null ? "Send failed" : String.valueOf(e.getCause().getMessage());
        }
    }

    /**
     * Next message: {@code "text", str}, {@code "binary", latin-1 str}, {@code "close", code, reason},
     * {@code "error", message} or {@code "timeout"} (timeout &lt;= 0 waits forever).
     */
    @HostAccess.Export
    public Object[] receive(double timeout) {
        long deadline = timeout > 0 ? System.nanoTime() + (long) (timeout * 1e9) : Long.MAX_VALUE;
        while (true) {
            run.checkStopping();
            try {
                Object[] m = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (m != null) {
                    if ("close".equals(m[0]) || "error".equals(m[0])) incoming.add(m); // stay closed for later reads
                    return m;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Object[] { "error", "Interrupted" };
            }
            if (System.nanoTime() > deadline) return new Object[] { "timeout" };
        }
    }

    @HostAccess.Export
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        run.untrack(this);
        WebSocket s = socket;
        if (s != null) {
            s.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(t -> null);
            s.abort();
        }
        incoming.add(new Object[] { "close", 1000, "" });
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (text.length() > maxMessage) {
                incoming.add(new Object[] { "error", "Message is too large" });
                ws.abort();
                return null;
            }
            if (last) {
                incoming.add(new Object[] { "text", text.toString() });
                text.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binary.writeBytes(chunk);
            if (binary.size() > maxMessage) {
                incoming.add(new Object[] { "error", "Message is too large" });
                ws.abort();
                return null;
            }
            if (last) {
                incoming.add(new Object[] { "binary", binary.toString(StandardCharsets.ISO_8859_1) });
                binary.reset();
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed = true;
            incoming.add(new Object[] { "close", statusCode, reason });
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed = true;
            incoming.add(new Object[] { "error", String.valueOf(error.getMessage()) });
        }
    }
}
