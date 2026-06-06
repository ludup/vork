package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class NotificationsSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-bell";
    }

    @Override
    public String getName() {
        return "Notifications";
    }

    @Override
    public String getDescription() {
        return "Configure notification providers for alerts and messages.";
    }

    @Override
    public String getPath() {
        return "notifications";
    }
}
