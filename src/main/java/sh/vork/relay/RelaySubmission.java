package sh.vork.relay;

/**
 * Response payload received from {@code GET /api/v1/relay/{sessionId}/response}.
 * Contains the AES-256-GCM encrypted response submitted by the browser.
 */
public record RelaySubmission(String encryptedResponse, String nonce, String authTag) {}
