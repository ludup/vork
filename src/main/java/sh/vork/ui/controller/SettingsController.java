package sh.vork.ui.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import sh.vork.ui.SettingsPage;
import sh.vork.ui.SettingsPageRegistry;

import java.util.List;

@Controller
@RequestMapping("/settings")
public class SettingsController {
    private final SettingsPageRegistry registry;

    @Autowired
    public SettingsController(SettingsPageRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("")
    public String settingsHome(Model model) {
        List<SettingsPage> pages = registry.getAllPages();
        model.addAttribute("pages", pages);
        return "settings";
    }

    @GetMapping("/{page}")
    public String settingsPage(@org.springframework.web.bind.annotation.PathVariable String page) {
        // Route to the correct Thymeleaf template for the settings page
        // e.g. /settings/change-password → settings/change-password.html
        return "settings/" + page;
    }
}
