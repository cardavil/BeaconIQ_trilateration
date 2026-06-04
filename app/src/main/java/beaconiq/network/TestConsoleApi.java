package beaconiq.network;

import android.util.Log;

import beaconiq.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TestConsoleApi {

    private static final String TAG = "BeaconIQ.Api";

    // Sourced from BuildConfig (populated from the untracked local.properties).
    public static final String ENDPOINT_URL = BuildConfig.ENDPOINT_URL;
    public static final String AUTH_TOKEN = BuildConfig.AUTH_TOKEN;
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    public static String postSession(JSONObject payload) throws IOException {
        String targetUrl = ENDPOINT_URL;
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        boolean usePost = true;

        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);

                if (usePost) {
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                    }
                } else {
                    conn.setRequestMethod("GET");
                }

                int code = conn.getResponseCode();

                if (code == 301 || code == 302 || code == 303
                        || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("Redirect with no Location header");
                    }
                    targetUrl = location;
                    if (code != 307 && code != 308) {
                        usePost = false;
                    }
                    continue;
                }

                return readBody(conn, code);
            } finally {
                conn.disconnect();
            }
        }

        throw new IOException("Too many redirects (" + MAX_REDIRECTS + ")");
    }

    public static String listSessions() throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("auth", AUTH_TOKEN);
            payload.put("action", "list_sessions");
        } catch (org.json.JSONException e) {
            throw new IOException("Failed to build request", e);
        }
        return postSession(payload);
    }

    private static String readBody(HttpURLConnection conn, int code) throws IOException {
        java.io.InputStream stream = (code >= 200 && code < 400)
                ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
        }

        if (code >= 400) {
            throw new IOException("HTTP " + code + ": " + sb);
        }

        String body = sb.toString();
        checkAppsScriptError(body);
        return body;
    }

    /**
     * Throws if {@code body} is a JSON object reporting an Apps Script error
     * ({@code {"status":"error", ...}}). Non-JSON or non-error bodies pass
     * through unchanged. Package-visible for unit testing.
     */
    static void checkAppsScriptError(String body) throws IOException {
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("status") && "error".equals(json.optString("status"))) {
                String msg = json.optString("message", "Unknown Apps Script error");
                throw new IOException("Apps Script error: " + msg);
            }
        } catch (org.json.JSONException ignored) {
        }
    }
}
