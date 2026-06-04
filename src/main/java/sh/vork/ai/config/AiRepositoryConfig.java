package sh.vork.ai.config;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.entity.AiSession;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.RepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiRepositoryConfig {

    @Bean
    public DatabaseRepository<AiSession> aiSessionRepository(RepositoryFactory factory) {
        return factory.create(AiSession.class);
    }

    @Bean
    public DatabaseRepository<AgentTemplate> agentTemplateRepository(RepositoryFactory factory) {
        return factory.create(AgentTemplate.class);
    }
}
