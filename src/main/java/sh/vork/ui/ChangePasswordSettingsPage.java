package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class ChangePasswordSettingsPage implements SettingsPage {
    @Override
    public String getIcon() {
        return "fa-key";
    }

    @Override
    public String getName() {
        return "Change Password";
    }

    @Override
    public String getDescription() {
        return "Change your administrator password.";
    }

    @Override
    public String getPath() {
        return "change-password";
    }
}
