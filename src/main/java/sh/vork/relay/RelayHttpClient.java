package sh.vork.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import sh.vork.relay.lib.model.RelaySubmission;

/**
 * Thin HTTP client for the vork-relay zero-knowledge relay protocol.
 *
 * <p>Uses {@link java.net.http.HttpClient} — no additional runtime dependencies needed.
 */
@Component
public class RelayHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RelayHttpClient.class);

    private final HttpClient  httpClient;
    private final ObjectMapper objectMapper;

    public RelayHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Uploads an encrypted form schema to {@code POST /api/v1/relay/{sessionId}}.
     *
     * @param relayBaseUrl base URL of the relay server (e.g. {@code https://relay.vork.sh})
     * @param sessionId    unique session identifier (must be a UUID)
     * @param ciphertext   base64url-encoded ciphertext
     * @param nonce        base64url-encoded 12-byte GCM IV
     * @param authTag      base64url-encoded 16-byte GCM authentication tag
     * @throws Exception if the relay returns a non-201 status or the request fails
     */
    public void upload(String relayBaseUrl, String sessionId,
                       String ciphertext, String nonce, String authTag,
                       int timeoutMinutes) throws Exception {
        log.debug("ENTER upload: relayBaseUrl={}, sessionId={}, timeoutMinutes={}", relayBaseUrl, sessionId, timeoutMinutes);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("encryptedSchema", ciphertext);
        payload.put("nonce", nonce);
        payload.put("authTag", authTag);
        if (timeoutMinutes > 0) {
            payload.put("timeoutMinutes", timeoutMinutes);
        }
        String body = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(relayBaseUrl + "/api/v1/relay/" + sessionId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Relay upload response: HTTP {}", response.statusCode());

        if (response.statusCode() != 201) {
            throw new RuntimeException(
                    "Relay upload failed: HTTP " + response.statusCode() + " — " + response.body());
        }
        log.debug("EXIT upload: OK [sessionId={}]", sessionId);
    }

    /**
     * Long-polls {@code GET /api/v1/relay/{sessionId}/response} for a submitted response.
     *
     * @param relayBaseUrl base URL of the relay server
     * @param sessionId    session identifier matching the original upload
     * @param timeoutMs    long-poll timeout in milliseconds (max 60 000 per relay spec)
     * @return the decrypted response envelope, or {@code null} on 204 (no response yet — caller should retry)
     * @throws Exception on network error or non-200/204 status
     */
    public RelaySubmission pollForResponse(String relayBaseUrl, String sessionId,
                                           int timeoutMs) throws Exception {
        log.debug("ENTER pollForResponse: sessionId={}, timeoutMs={}", sessionId, timeoutMs);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(relayBaseUrl + "/api/v1/relay/" + sessionId
                        + "/response?timeoutMs=" + timeoutMs))
                .GET()
                // Add extra buffer beyond the long-poll timeout so the HTTP layer
                // doesn't cut the connection before the relay responds with 204
                .timeout(Duration.ofMillis(timeoutMs + 15_000L))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Relay poll response: HTTP {} [sessionId={}]", response.statusCode(), sessionId);

        if (response.statusCode() == 204) {
            log.debug("EXIT pollForResponse: timeout (204) [sessionId={}]", sessionId);
            return null;
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Relay poll failed: HTTP " + response.statusCode() + " — " + response.body());
        }

        RelaySubmission submission = objectMapper.readValue(response.body(), RelaySubmission.class);
        log.debug("EXIT pollForResponse: response received [sessionId={}]", sessionId);
        return submission;
    }
}
