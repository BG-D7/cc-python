package work.diffdev.kik.python.net;

import work.diffdev.kik.python.PyRun;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/** HTTP for Python's requests module. Every hop of a redirect is checked against the rules again. */
public final class HttpBridge {
    private static final int MAX_REDIRECTS = 16;
    /** Headers Java's client manages itself and refuses to set. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private static final ExecutorService BODY_READERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "KiK-Python-HTTP-Body");
        t.setDaemon(true);
        return t;
    });

    private HttpBridge() {}

    /**
     * Result: {@code "ok", status, reason, url, headers (flat name/value pairs), body (bytes as latin-1)} or
     * {@code "error", message}.
     */
    public static Object[] request(PyRun run, String method, String url, Map<String, String> headers, byte[] body,
                                   double timeout, boolean followRedirects) {
        long timeoutMs = timeout > 0 ? (long) (timeout * 1000) : 0;
        String m = method.toUpperCase(Locale.ROOT);
        try {
            for (int hop = 0; ; hop++) {
                NetRules.Checked checked = NetRules.check(url, false);
                if (body != null && body.length > checked.options().maxUpload()) return error("Request body is too large");

                HttpRequest.Builder b = HttpRequest.newBuilder(checked.uri());
                if (timeoutMs > 0) b.timeout(Duration.ofMillis(timeoutMs));
                boolean hasAgent = false;
                for (var e : headers.entrySet()) {
                    String name = e.getKey().toLowerCase(Locale.ROOT);
                    if (RESTRICTED.contains(name)) continue;
                    hasAgent |= name.equals("user-agent");
                    b.header(e.getKey(), e.getValue());
                }
                if (!hasAgent) b.header("User-Agent", "computercraft-python");
                b.method(m, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));

                HttpResponse<InputStream> resp = run.await(CLIENT.sendAsync(b.build(), HttpResponse.BodyHandlers.ofInputStream()), timeoutMs);
                int status = resp.statusCode();
                var location = resp.headers().firstValue("location");
                if (followRedirects && location.isPresent() && status >= 300 && status < 400 && status != 304) {
                    resp.body().close();
                    if (hop >= MAX_REDIRECTS) return error("Too many redirects");
                    url = checked.uri().resolve(URI.create(location.get().replace(" ", "%20"))).toString();
                    if (status == 303 || ((status == 301 || status == 302) && !m.equals("GET") && !m.equals("HEAD"))) {
                        m = "GET";
                        body = null;
                    }
                    continue;
                }

                // A stalled body would block a plain read forever (the request timeout covers only the headers), so
                // read it on another thread; Ctrl+T or the timeout closes the stream, which unblocks that thread.
                byte[] data;
                long limit = checked.options().maxDownload();
                try (InputStream in = resp.body()) {
                    data = run.await(CompletableFuture.supplyAsync(() -> readLimited(in, limit), BODY_READERS), timeoutMs);
                }
                if (data == null) return error("Response is too large");

                List<Object> flat = new ArrayList<>();
                resp.headers().map().forEach((k, vs) -> {
                    for (String v : vs) {
                        flat.add(k);
                        flat.add(v);
                    }
                });
                return new Object[] { "ok", status, reason(status), checked.uri().toString(), flat.toArray(),
                    new String(data, StandardCharsets.ISO_8859_1) };
            }
        } catch (NetRules.Denied e) {
            return error(e.getMessage());
        } catch (TimeoutException e) {
            return error("Timed out");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return error(c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
        } catch (IOException | IllegalArgumentException e) {
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static byte[] readLimited(InputStream in, long limit) {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) return null;
            }
        } catch (IOException e) {
            throw new CompletionException(e);
        }
        return out.toByteArray();
    }

    private static Object[] error(String message) {
        return new Object[] { "error", message };
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }
}
