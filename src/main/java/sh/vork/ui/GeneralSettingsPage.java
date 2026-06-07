package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class GeneralSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-sliders";
    }

    @Override
    public String getName() {
        return "General";
    }

    @Override
    public String getDescription() {
        return "Application base URL and system-wide defaults.";
    }

    @Override
    public String getPath() {
        return "general";
    }
}
