package work.diffdev.kik.python.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class JavaResponse {
    public final int status_code;
    public final byte[] bodyBytes;
    public final Map<String, List<String>> headers;

    public JavaResponse(int statusCode, byte[] bodyBytes, Map<String, List<String>> headers) {
        this.status_code = statusCode;
        this.bodyBytes = bodyBytes != null ? bodyBytes : new byte[0];
        this.headers = headers;
    }

    public int getStatusCode() {
        return status_code;
    }

    public String getBody() {
        return new String(bodyBytes, StandardCharsets.UTF_8);
    }

    public byte[] getBytes() {
        return bodyBytes;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }
}
