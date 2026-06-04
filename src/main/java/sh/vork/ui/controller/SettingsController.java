package sh.vork.ui.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import sh.vork.ai.registry.ToolDescriptor;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.ui.SettingsPage;
import sh.vork.ui.SettingsPageRegistry;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {
    private final SettingsPageRegistry registry;
    private final ToolRegistry toolRegistry;

    @Autowired
    public SettingsController(SettingsPageRegistry registry, ToolRegistry toolRegistry) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("")
    public String settingsHome(Model model) {
        List<SettingsPage> pages = registry.getAllPages();
        model.addAttribute("pages", pages);
        return "settings";
    }

    @GetMapping("/tool-inspector")
    public String toolInspector(Model model) {
        Map<String, List<ToolDescriptor>> toolsByCategory = toolRegistry.getToolsByCategory();
        int toolCount = toolsByCategory.values().stream().mapToInt(List::size).sum();
        model.addAttribute("toolsByCategory", toolsByCategory);
        model.addAttribute("toolCount", toolCount);
        return "settings/tool-inspector";
    }

    @GetMapping("/{page}")
    public String settingsPage(@org.springframework.web.bind.annotation.PathVariable String page) {
        return "settings/" + page;
    }
}
