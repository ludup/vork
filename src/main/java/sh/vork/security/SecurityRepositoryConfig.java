package sh.vork.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;

/**
 * Configuration for security-related repositories.
 * Registers the DatabaseRepository for AuthorizationRequest.
 */
@Configuration
public class SecurityRepositoryConfig {

    @Bean
    public DatabaseRepository<AuthorizationRequest> authorizationRequestRepository(
            DatabaseRepositoryFactory factory) {
        return factory.create(AuthorizationRequest.class);
    }
}
