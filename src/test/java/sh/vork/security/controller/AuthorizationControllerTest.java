package sh.vork.security.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.security.AuthorizationRequest;
import sh.vork.security.AuthorizationTokenService;

class AuthorizationControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approveAuthorization_whenUnauthenticated_returnsUnauthorized() {
        AuthorizationTokenService tokenService = org.mockito.Mockito.mock(AuthorizationTokenService.class);
        AuthorizationController controller = new AuthorizationController(tokenService);

        AuthorizationController.ApprovalRequest requestBody = new AuthorizationController.ApprovalRequest();
        requestBody.setToken("tok-1");

        ResponseEntity<?> response = controller.approveAuthorization(requestBody);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(tokenService, never()).approveAuthorization(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void approveAuthorization_whenAuthenticated_approvesUsingPrincipalName() {
        AuthorizationTokenService tokenService = org.mockito.Mockito.mock(AuthorizationTokenService.class);
        AuthorizationController controller = new AuthorizationController(tokenService);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "pw", "ROLE_USER"));

        when(tokenService.getAuthorizationRequest("tok-2")).thenReturn(validPendingRequest("tok-2"));

        AuthorizationController.ApprovalRequest requestBody = new AuthorizationController.ApprovalRequest();
        requestBody.setToken("tok-2");

        ResponseEntity<?> response = controller.approveAuthorization(requestBody);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(tokenService).approveAuthorization("tok-2", "alice");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertEquals("approved", payload.get("status"));
    }

    @Test
    void denyAuthorization_whenUnauthenticated_returnsUnauthorized() {
        AuthorizationTokenService tokenService = org.mockito.Mockito.mock(AuthorizationTokenService.class);
        AuthorizationController controller = new AuthorizationController(tokenService);

        AuthorizationController.DenialRequest requestBody = new AuthorizationController.DenialRequest();
        requestBody.setToken("tok-3");

        ResponseEntity<?> response = controller.denyAuthorization(requestBody);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(tokenService, never()).denyAuthorization(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void denyAuthorization_whenAuthenticated_deniesUsingPrincipalName() {
        AuthorizationTokenService tokenService = org.mockito.Mockito.mock(AuthorizationTokenService.class);
        AuthorizationController controller = new AuthorizationController(tokenService);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("bob", "pw", "ROLE_USER"));

        when(tokenService.getAuthorizationRequest("tok-4")).thenReturn(validPendingRequest("tok-4"));

        AuthorizationController.DenialRequest requestBody = new AuthorizationController.DenialRequest();
        requestBody.setToken("tok-4");

        ResponseEntity<?> response = controller.denyAuthorization(requestBody);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(tokenService).denyAuthorization("tok-4", "bob");

        Object body = response.getBody();
        assertInstanceOf(Map.class, body);
    }

    private static AuthorizationRequest validPendingRequest(String token) {
        long now = System.currentTimeMillis();
        return new AuthorizationRequest(
                token,
                "session-1",
                "executeTerminalCommand",
                "{}",
                "desc",
                now,
                now + 60_000,
                AuthorizationRequest.AuthorizationStatus.PENDING,
                0,
                "");
    }
}
