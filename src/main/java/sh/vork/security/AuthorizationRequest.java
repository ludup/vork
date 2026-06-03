package sh.vork.security;

import com.jadaptive.orm.DatabaseEntity;

/**
 * Authorization request entity stored in MongoDB.
 * Represents a request to authorize a specific action (e.g., tool execution).
 * 
 * Each authorization request has:
 * - Unique cryptographically secure token
 * - Action details (tool name, arguments, etc.)
 * - Approval status and timestamp
 * - Expiration time (tokens are valid for 24 hours by default)
 */
public record AuthorizationRequest(
        String uuid,                  // Unique authorization token (used as MongoDB _id)
        String sessionUuid,           // HTTP session ID that initiated this request
        String toolName,              // Name of the tool being authorized
        String arguments,             // JSON string of arguments (optional)
        String description,           // Human-readable description of the action
        long createdAt,               // Timestamp when request was created (epoch ms)
        long expiresAt,               // Timestamp when token expires (epoch ms)
        AuthorizationStatus status,   // Current status: PENDING, APPROVED, DENIED
        long approvedAt,              // Timestamp when approved/denied (0 if not yet decided)
        String approvedBy             // Username who approved/denied this (empty if pending)
) implements DatabaseEntity {

    /**
     * Status enumeration for authorization requests
     */
    public enum AuthorizationStatus {
        PENDING,   // Awaiting user decision
        APPROVED,  // User approved the action
        DENIED,    // User denied the action
        EXPIRED    // Token has expired
    }

    /**
     * Check if this authorization request is still valid (not expired, still pending)
     */
    public boolean isValid() {
        return status == AuthorizationStatus.PENDING && System.currentTimeMillis() < expiresAt;
    }

    /**
     * Check if this authorization request has been approved
     */
    public boolean isApproved() {
        return status == AuthorizationStatus.APPROVED;
    }

    /**
     * Create an authorized version of this request
     */
    public AuthorizationRequest withApproval(String username) {
        return new AuthorizationRequest(
                this.uuid,
                this.sessionUuid,
                this.toolName,
                this.arguments,
                this.description,
                this.createdAt,
                this.expiresAt,
                AuthorizationStatus.APPROVED,
                System.currentTimeMillis(),
                username
        );
    }

    /**
     * Create a denied version of this request
     */
    public AuthorizationRequest withDenial(String username) {
        return new AuthorizationRequest(
                this.uuid,
                this.sessionUuid,
                this.toolName,
                this.arguments,
                this.description,
                this.createdAt,
                this.expiresAt,
                AuthorizationStatus.DENIED,
                System.currentTimeMillis(),
                username
        );
    }
}
