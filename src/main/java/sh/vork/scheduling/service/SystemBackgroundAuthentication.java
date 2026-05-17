package sh.vork.scheduling.service;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authentication token used by headless background scheduler executions.
 */
public class SystemBackgroundAuthentication extends AbstractAuthenticationToken {

    private final String username;

    public SystemBackgroundAuthentication(String username) {
        super(List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_SYSTEM")));
        this.username = (username == null || username.isBlank()) ? "system" : username;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "N/A";
    }

    @Override
    public Object getPrincipal() {
        return username;
    }

    @Override
    public String getName() {
        return username;
    }
}
