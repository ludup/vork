package sh.vork.setup;

import java.io.IOException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Intercepts every HTTP request and redirects to the setup wizard when no
 * admin account exists yet.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it runs before Spring
 * Security's filter chain.  Paths listed in {@link #BYPASS_PREFIXES} are always
 * allowed through so that the wizard page, static assets, and auth-related
 * endpoints remain accessible without authentication.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SetupCheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SetupCheckFilter.class);

    /** Request path prefixes that are never redirected to /setup. */
    private static final Set<String> BYPASS_PREFIXES = Set.of(
            "/setup",
            "/api/setup",
            "/api/system",
            "/css/",
            "/js/",
            "/images/",
            "/login",
            "/logout",
            "/actuator",
            "/ws/",
            "/api/authorization/"
    );

    private final SetupService setupService;

    public SetupCheckFilter(SetupService setupService) {
        this.setupService = setupService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (setupService.isSetupRequired()) {
            String path = request.getServletPath();
            boolean bypassed = path.equals("/favicon.ico")
                    || BYPASS_PREFIXES.stream().anyMatch(path::startsWith);
            if (!bypassed) {
                log.debug("Setup required — redirecting {} to /setup", path);
                response.sendRedirect(request.getContextPath() + "/setup");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
