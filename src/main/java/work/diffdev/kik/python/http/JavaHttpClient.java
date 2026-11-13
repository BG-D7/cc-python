package work.diffdev.kik.python.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class JavaHttpClient {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    public static JavaResponse request(String method, String url, String body, Map<String, String> headers, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 30));

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        HttpRequest.BodyPublisher publisher = (body != null) ? 
            HttpRequest.BodyPublishers.ofString(body) : 
            HttpRequest.BodyPublishers.noBody();

        builder.method(method.toUpperCase(), publisher);

        HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new JavaResponse(response.statusCode(), response.body(), response.headers().map());
    }

    public static CompletableFuture<JavaResponse> requestAsync(String method, String url, String body, Map<String, String> headers, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 30));

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        HttpRequest.BodyPublisher publisher = (body != null) ? 
            HttpRequest.BodyPublishers.ofString(body) : 
            HttpRequest.BodyPublishers.noBody();

        builder.method(method.toUpperCase(), publisher);

        return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(resp -> new JavaResponse(resp.statusCode(), resp.body(), resp.headers().map()));
    }

    public static JavaResponse get(String url, Map<String, String> headers) throws Exception {
        return request("GET", url, null, headers, 30);
    }

    public static JavaResponse post(String url, String body, Map<String, String> headers) throws Exception {
        return request("POST", url, body, headers, 30);
    }

    public static CompletableFuture<JavaResponse> getAsync(String url, Map<String, String> headers) {
        return requestAsync("GET", url, null, headers, 30);
    }

    public static CompletableFuture<JavaResponse> postAsync(String url, String body, Map<String, String> headers) {
        return requestAsync("POST", url, body, headers, 30);
    }
}
