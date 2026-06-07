package sh.vork.ai.telegram;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Issues and validates short-lived tokens for the generic input-form redirect flow.
 *
 * <p>When a tool suspension requires a complex form (password fields or multiple inputs),
 * the system sends the user a URL that embeds a token generated here.  The token is valid
 * for 15 minutes and is single-use (consumed on first successful submission).
 */
@Service
public class InputFormTokenService {

    private static final Logger log = LoggerFactory.getLogger(InputFormTokenService.class);
    private static final int    TTL_MINUTES = 15;

    public record TokenClaims(String sessionUuid, String eventId, String username) {}

    private final Map<String, TokenEntry> store = new ConcurrentHashMap<>();

    private record TokenEntry(TokenClaims claims, Instant expiresAt) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a token and stores it.
     *
     * @return an opaque 32-hex-char token string
     */
    public String generateToken(String sessionUuid, String eventId, String username) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plusSeconds(TTL_MINUTES * 60L);
        store.put(token, new TokenEntry(new TokenClaims(sessionUuid, eventId, username), expiresAt));
        log.debug("Form token issued [session={}, event={}, expiresAt={}]", sessionUuid, eventId, expiresAt);
        return token;
    }

    /**
     * Validates the token and returns its claims if valid and unexpired.
     *
     * @return claims, or {@code null} if the token is unknown or expired
     */
    public TokenClaims validateToken(String token) {
        if (token == null || token.isBlank()) return null;
        TokenEntry entry = store.get(token);
        if (entry == null) return null;
        if (Instant.now().isAfter(entry.expiresAt())) {
            store.remove(token);
            return null;
        }
        return entry.claims();
    }

    /**
     * Consumes (removes) a token after use so it cannot be replayed.
     */
    public void consumeToken(String token) {
        store.remove(token);
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    /** Removes expired tokens every 5 minutes. */
    @Scheduled(fixedDelay = 300_000)
    public void purgeExpired() {
        Instant now = Instant.now();
        int removed = 0;
        for (Map.Entry<String, TokenEntry> e : store.entrySet()) {
            if (now.isAfter(e.getValue().expiresAt())) {
                store.remove(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Purged {} expired Telegram form tokens", removed);
        }
    }
}
