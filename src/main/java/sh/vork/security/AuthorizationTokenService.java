package sh.vork.security;

import org.springframework.stereotype.Service;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.SearchQuery;
import sh.vork.database.SortOrder;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.stream.Stream;

/**
 * Service for managing authorization requests and tokens.
 * 
 * Responsibilities:
 * - Generate unique, cryptographically secure authorization tokens
 * - Create and store authorization requests in MongoDB
 * - Retrieve and verify authorization requests
 * - Mark requests as approved or denied
 * - Clean up expired tokens
 */
@Service
public class AuthorizationTokenService {

    private final DatabaseRepository<AuthorizationRequest> authorizationRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthorizationTokenService(DatabaseRepository<AuthorizationRequest> authorizationRepository) {
        this.authorizationRepository = authorizationRepository;
    }

    /**
     * Generate a unique, cryptographically secure authorization token.
     * 
     * Token format: Base64-encoded 32-byte random value
     * This ensures tokens cannot be guessed and are sufficiently unique.
     * 
     * @return Unique authorization token
     */
    public String generateUniqueToken() {
        byte[] randomBytes = new byte[32];  // 256-bit random value
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Create and store a new authorization request.
     * 
     * @param sessionUuid HTTP session ID
     * @param toolName Name of the tool being authorized
     * @param arguments JSON arguments for the tool (optional)
     * @param description Human-readable description
     * @return Created authorization request (includes the unique token)
     */
    public AuthorizationRequest createAuthorizationRequest(
            String sessionUuid,
            String toolName,
            String arguments,
            String description) {

        String token = generateUniqueToken();
        long now = System.currentTimeMillis();
        long expiresAt = now + (24 * 60 * 60 * 1000);  // 24-hour expiration

        AuthorizationRequest request = new AuthorizationRequest(
                token,                                    // uuid = unique token
                sessionUuid,
                toolName,
                arguments,
                description,
                now,
                expiresAt,
                AuthorizationRequest.AuthorizationStatus.PENDING,
                0,                                        // Not yet approved/denied
                ""                                        // No approval user yet
        );

        authorizationRepository.save(request);
        return request;
    }

    /**
     * Retrieve an authorization request by token.
     * 
     * @param token Authorization token
     * @return Authorization request, or null if not found
     */
    public AuthorizationRequest getAuthorizationRequest(String token) {
        return authorizationRepository.get(token);
    }

    /**
     * Mark an authorization request as approved.
     * 
     * @param token Authorization token
     * @param username Username of approver
     * @return Updated authorization request
     */
    public AuthorizationRequest approveAuthorization(String token, String username) {
        AuthorizationRequest request = authorizationRepository.get(token);
        if (request == null) {
            return null;
        }

        AuthorizationRequest approved = request.withApproval(username);
        authorizationRepository.save(approved);
        return approved;
    }

    /**
     * Mark an authorization request as denied.
     * 
     * @param token Authorization token
     * @param username Username of denier
     * @return Updated authorization request
     */
    public AuthorizationRequest denyAuthorization(String token, String username) {
        AuthorizationRequest request = authorizationRepository.get(token);
        if (request == null) {
            return null;
        }

        AuthorizationRequest denied = request.withDenial(username);
        authorizationRepository.save(denied);
        return denied;
    }

    /**
     * Check if an authorization request is valid and approved.
     * 
     * @param token Authorization token
     * @return true if token is valid, not expired, and approved
     */
    public boolean isAuthorizationApproved(String token) {
        AuthorizationRequest request = authorizationRepository.get(token);
        if (request == null) {
            return false;
        }

        // Check if expired
        if (System.currentTimeMillis() > request.expiresAt()) {
            return false;
        }

        // Check if approved
        return request.isApproved();
    }

    /**
     * Get authorization requests for a specific session.
     * 
     * @param sessionUuid HTTP session ID
     * @return Stream of authorization requests for this session
     */
    public Stream<AuthorizationRequest> getSessionAuthorizationRequests(String sessionUuid) {
        return authorizationRepository.search(0, 100, "createdAt", SortOrder.DESC,
                SearchQuery.eq("sessionUuid", sessionUuid));
    }

    /**
     * Clean up expired authorization requests.
     * Should be called periodically (e.g., via @Scheduled task).
     * 
     * This finds all PENDING requests that have expired and marks them as EXPIRED.
     */
    public void cleanupExpiredAuthorizations() {
        long now = System.currentTimeMillis();
        
        // Find all pending requests that have expired
        try (Stream<AuthorizationRequest> expiredRequests = 
             authorizationRepository.search(0, 1000, "createdAt", SortOrder.ASC,
                     SearchQuery.eq("status", "PENDING"),
                     SearchQuery.lt("expiresAt", now))) {
            
            expiredRequests.forEach(request -> {
                // Mark as expired
                AuthorizationRequest expired = new AuthorizationRequest(
                        request.uuid(),
                        request.sessionUuid(),
                        request.toolName(),
                        request.arguments(),
                        request.description(),
                        request.createdAt(),
                        request.expiresAt(),
                        AuthorizationRequest.AuthorizationStatus.EXPIRED,
                        now,
                        "system"
                );
                authorizationRepository.save(expired);
            });
        }
    }
}
