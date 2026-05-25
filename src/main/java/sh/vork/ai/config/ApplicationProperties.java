package sh.vork.ai.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ApplicationProperties {


    @Autowired
    private Environment env;

    public String getValue(String key, String defaultValue) {
        return env.getProperty(key, defaultValue);
    }

    public int getValue(String key, int defaultValue) {
        return Integer.parseInt(getValue(key, String.valueOf(defaultValue)));
    }

    public boolean getValue(String key, boolean defaultValue) {
        return Boolean.parseBoolean(getValue(key, String.valueOf(defaultValue)));   
    }

    public String getValue(String key) {
        return env.getProperty(key);
    }
}
