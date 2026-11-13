package work.diffdev.kik.python.http;

import java.util.List;
import java.util.Map;

public class JavaResponse {
    public final int status_code;
    public final String body;
    public final Map<String, List<String>> headers;

    public JavaResponse(int statusCode, String body, Map<String, List<String>> headers) {
        this.status_code = statusCode;
        this.body = body;
        this.headers = headers;
    }

    public int getStatusCode() {
        return status_code;
    }

    public String getBody() {
        return body != null ? body : "";
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }
}
