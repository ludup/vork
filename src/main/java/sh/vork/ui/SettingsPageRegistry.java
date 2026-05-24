package sh.vork.ui;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SettingsPageRegistry {
    private final ApplicationContext context;

    @Autowired
    public SettingsPageRegistry(ApplicationContext context) {
        this.context = context;
    }

    public List<SettingsPage> getAllPages() {
        return context.getBeansOfType(SettingsPage.class)
                .values()
                .stream()
                .collect(Collectors.toList());
    }
}
