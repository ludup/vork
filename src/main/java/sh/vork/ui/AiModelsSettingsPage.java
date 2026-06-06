package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class AiModelsSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-robot";
    }

    @Override
    public String getName() {
        return "AI Models";
    }

    @Override
    public String getDescription() {
        return "Configure AI providers and model selection.";
    }

    @Override
    public String getPath() {
        return "ai-models";
    }
}
