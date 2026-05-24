package sh.vork.security.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import sh.vork.security.AuthorizationRequest;
import sh.vork.security.AuthorizationTokenService;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for authorization endpoints.
 * 
 * Endpoints:
 * - GET  /api/authorization/details?token={token}     - Get authorization request details
 * - POST /api/authorization/approve                    - Approve an authorization
 * - POST /api/authorization/deny                       - Deny an authorization
 */
@RestController
@RequestMapping("/api/authorization")
public class AuthorizationController {

    private final AuthorizationTokenService authorizationTokenService;

    public AuthorizationController(AuthorizationTokenService authorizationTokenService) {
        this.authorizationTokenService = authorizationTokenService;
    }

    /**
     * Get authorization request details by token.
     * 
     * @param token Authorization token
     * @return Authorization request details
     */
    @GetMapping("/details")
    public ResponseEntity<?> getAuthorizationDetails(@RequestParam String token) {
        AuthorizationRequest request = authorizationTokenService.getAuthorizationRequest(token);
        
        if (request == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Authorization token not found"));
        }

        if (!request.isValid()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "Authorization token has expired"));
        }

        // Return details for display on authorization page
        Map<String, Object> details = new HashMap<>();
        details.put("token", token);
        details.put("toolName", request.toolName());
        details.put("description", request.description());
        details.put("arguments", request.arguments());
        details.put("createdAt", request.createdAt());
        details.put("expiresAt", request.expiresAt());

        return ResponseEntity.ok(details);
    }

    /**
         * Approve an authorization request for the currently authenticated user.
     * 
     * Request body:
     * {
     *   "token": "...",
     * }
     * 
     * @return Approval response with redirect URL
     */
    @PostMapping("/approve")
    public ResponseEntity<?> approveAuthorization(@RequestBody ApprovalRequest approvalRequest) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required before approval"));
        }

        // Validate token
        AuthorizationRequest request = authorizationTokenService.getAuthorizationRequest(
            approvalRequest.getToken());
        
        if (request == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Authorization token not found"));
        }

        if (!request.isValid()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "Authorization token has expired"));
        }

        // Approve the authorization
        authorizationTokenService.approveAuthorization(
            approvalRequest.getToken(),
            auth.getName());

        // Return success response
        Map<String, Object> response = new HashMap<>();
        response.put("status", "approved");
        response.put("message", "Authorization approved");
        response.put("toolName", request.toolName());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Deny an authorization request.
     * 
     * Request body:
     * {
     *   "token": "..."
     * }
     * 
     * @return Denial response
     */
    @PostMapping("/deny")
    public ResponseEntity<?> denyAuthorization(@RequestBody DenialRequest denialRequest) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required before denial"));
        }

        // Validate token
        AuthorizationRequest request = authorizationTokenService.getAuthorizationRequest(
            denialRequest.getToken());
        
        if (request == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Authorization token not found"));
        }

        if (!request.isValid()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "Authorization token has expired"));
        }

        // Deny the authorization as the authenticated user.
        authorizationTokenService.denyAuthorization(denialRequest.getToken(), auth.getName());

        // Return success response
        Map<String, String> response = new HashMap<>();
        response.put("status", "denied");
        response.put("message", "Authorization denied");

        return ResponseEntity.ok(response);
    }

    /**
     * Request body for approval endpoint
     */
    public static class ApprovalRequest {
        public String token;

        // Getters for Jackson
        public String getToken() { return token; }

        // Setters for Jackson
        public void setToken(String token) { this.token = token; }
    }

    /**
     * Request body for denial endpoint
     */
    public static class DenialRequest {
        public String token;

        // Getters for Jackson
        public String getToken() { return token; }

        // Setters for Jackson
        public void setToken(String token) { this.token = token; }
    }
}
