package sh.vork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    scanBasePackages = {"sh.vork", "com.jadaptive.orm.mongo"},
    excludeName = {
        // Excluded so the app starts without credentials configured.
        // The Gemini client is built programmatically by AiChatClientFactory
        // once the user stores an API key through the setup UI.
        "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration"
    }
)
public class VorkApplication {
    public static void main(String[] args) {
        SpringApplication.run(VorkApplication.class, args);
    }
}
