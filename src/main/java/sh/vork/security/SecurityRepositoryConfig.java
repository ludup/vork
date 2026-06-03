package sh.vork.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.RepositoryFactory;

/**
 * Configuration for security-related repositories.
 * Registers the DatabaseRepository for AuthorizationRequest and VorkUser.
 */
@Configuration
public class SecurityRepositoryConfig {

    @Bean
    public DatabaseRepository<AuthorizationRequest> authorizationRequestRepository(
            RepositoryFactory factory) {
        return factory.create(AuthorizationRequest.class);
    }

    @Bean
    public DatabaseRepository<VorkUser> vorkUserRepository(RepositoryFactory factory) {
        return factory.create(VorkUser.class);
    }
}
