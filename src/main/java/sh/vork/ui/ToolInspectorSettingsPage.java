package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class ToolInspectorSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-wrench";
    }

    @Override
    public String getName() {
        return "Tool Inspector";
    }

    @Override
    public String getDescription() {
        return "Browse all AI tool callbacks registered with the system.";
    }

    @Override
    public String getPath() {
        return "tool-inspector";
    }
}
