package sh.vork.security.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
    private final AuthenticationManager authenticationManager;

    public AuthorizationController(AuthorizationTokenService authorizationTokenService,
                                  AuthenticationManager authenticationManager) {
        this.authorizationTokenService = authorizationTokenService;
        this.authenticationManager = authenticationManager;
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
     * Approve an authorization request with credential verification.
     * 
     * Request body:
     * {
     *   "token": "...",
     *   "username": "...",
     *   "password": "...",
     *   "rememberMe": true/false
     * }
     * 
     * @return Approval response with redirect URL
     */
    @PostMapping("/approve")
    public ResponseEntity<?> approveAuthorization(@RequestBody ApprovalRequest approvalRequest) {
        // Validate token
        AuthorizationRequest request = authorizationTokenService.getAuthorizationRequest(
                approvalRequest.token);
        
        if (request == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Authorization token not found"));
        }

        if (!request.isValid()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "Authorization token has expired"));
        }

        // Verify credentials
        try {
            UsernamePasswordAuthenticationToken authRequest = 
                    new UsernamePasswordAuthenticationToken(
                            approvalRequest.username,
                            approvalRequest.password);
            
            Authentication auth = authenticationManager.authenticate(authRequest);
            
            if (!auth.isAuthenticated()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid credentials"));
            }
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        // Approve the authorization
        authorizationTokenService.approveAuthorization(
                approvalRequest.token,
                approvalRequest.username);

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
        // Validate token
        AuthorizationRequest request = authorizationTokenService.getAuthorizationRequest(
                denialRequest.token);
        
        if (request == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Authorization token not found"));
        }

        if (!request.isValid()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "Authorization token has expired"));
        }

        // Deny the authorization (mark as denied by "anonymous" user - no login required for deny)
        authorizationTokenService.denyAuthorization(denialRequest.token, "denied");

        // Return success response
        Map<String, String> response = new HashMap<>();
        response.put("status", "denied");
        response.put("message", "Authorization denied");

        return ResponseEntity.ok(response);
    }

    /**
     * Request body for approval endpoint
     */
    @SuppressWarnings("unused")
    public static class ApprovalRequest {
        public String token;
        public String username;
        public String password;
        public boolean rememberMe;

        // Getters for Jackson
        public String getToken() { return token; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public boolean isRememberMe() { return rememberMe; }

        // Setters for Jackson
        public void setToken(String token) { this.token = token; }
        public void setUsername(String username) { this.username = username; }
        public void setPassword(String password) { this.password = password; }
        public void setRememberMe(boolean rememberMe) { this.rememberMe = rememberMe; }
    }

    /**
     * Request body for denial endpoint
     */
    @SuppressWarnings("unused")
    public static class DenialRequest {
        public String token;

        // Getters for Jackson
        public String getToken() { return token; }

        // Setters for Jackson
        public void setToken(String token) { this.token = token; }
    }
}
