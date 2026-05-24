package sh.vork.ui.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.beans.factory.annotation.Autowired;

import sh.vork.database.DatabaseRepository;
import sh.vork.security.VorkUser;
import sh.vork.security.DatabaseUserDetailsService;

@Controller
@RequestMapping("/settings/change-password")
@SessionAttributes("changePasswordForm")
public class ChangePasswordController {
    private final PasswordEncoder passwordEncoder;
    private final DatabaseRepository<VorkUser> userRepository;
    private final DatabaseUserDetailsService userDetailsService;

    @Autowired
    public ChangePasswordController(PasswordEncoder passwordEncoder,
                                   DatabaseRepository<VorkUser> userRepository,
                                   DatabaseUserDetailsService userDetailsService) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.userDetailsService = userDetailsService;
    }

    @ModelAttribute("changePasswordForm")
    public ChangePasswordForm changePasswordForm() {
        return new ChangePasswordForm();
    }

    @GetMapping("")
    public String showForm() {
        return "settings/change-password";
    }

    @PostMapping("")
    public String changePassword(@ModelAttribute("changePasswordForm") ChangePasswordForm form, Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();

        String currentPassword = form.getCurrentPassword();
        String newPassword = form.getNewPassword();
        String confirmPassword = form.getConfirmPassword();

        // Validate form inputs
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "New passwords do not match.");
            return "settings/change-password";
        }
        if (newPassword.length() < 8) {
            model.addAttribute("error", "Password must be at least 8 characters.");
            return "settings/change-password";
        }
        if (currentPassword.isEmpty()) {
            model.addAttribute("error", "Current password is required.");
            return "settings/change-password";
        }

        // Load user from database
        VorkUser user = userRepository.get(username);
        if (user == null) {
            model.addAttribute("error", "User not found.");
            return "settings/change-password";
        }

        // Verify current password matches stored hash
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            model.addAttribute("error", "Current password is incorrect.");
            return "settings/change-password";
        }

        // Update password via UserDetailsService
        if (userDetailsService.updatePassword(username, newPassword)) {
            model.addAttribute("success", "Password changed successfully.");
            form.setCurrentPassword("");
            form.setNewPassword("");
            form.setConfirmPassword("");
        } else {
            model.addAttribute("error", "Failed to update password. Please try again.");
        }

        return "settings/change-password";
    }

    public static class ChangePasswordForm {
        private String currentPassword;
        private String newPassword;
        private String confirmPassword;
        public String getCurrentPassword() { return currentPassword; }
        public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
        public String getConfirmPassword() { return confirmPassword; }
        public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
    }
}
