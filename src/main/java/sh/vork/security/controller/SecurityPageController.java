package sh.vork.security.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Controller for security pages (login, authorization).
 * Serves Thymeleaf templates for custom login forms.
 */
@Controller
public class SecurityPageController {

    /**
     * Display the login page.
     * The login form posts to /login (handled by Spring Security).
     * 
     * Query parameters:
     * - error: Set to "true" if previous login failed
     * - logout: Set to "true" if user just logged out
     * - expired: Set to "true" if session expired
     * 
     * @param error Whether login error occurred
     * @param logout Whether user just logged out
     * @param expired Whether session expired
     * @param model Thymeleaf model
     * @return View name "login"
     */
    @GetMapping("/login")
    public String login(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout,
            @RequestParam(required = false) String expired,
            Model model) {
        
        if (error != null) {
            model.addAttribute("error", true);
        }
        if (logout != null) {
            model.addAttribute("logout", true);
        }
        if (expired != null) {
            model.addAttribute("expired", true);
        }
        
        return "login";
    }

    /**
     * Display the authorization page.
     * The user is shown action details and must verify credentials to approve.
     * 
     * Query parameter:
     * - token: Unique authorization token (required)
     * 
     * @param token Authorization token
     * @param model Thymeleaf model
     * @return View name "authorization"
     */
    @GetMapping("/authorize")
    public String authorize(
            @RequestParam(required = false) String token,
            Model model) {
        
        if (token != null) {
            model.addAttribute("token", token);
        }
        
        return "authorization";
    }
}
