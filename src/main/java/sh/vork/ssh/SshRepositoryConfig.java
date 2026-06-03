package sh.vork.ssh;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.RepositoryFactory;

@Configuration
public class SshRepositoryConfig {

    @Bean
    public DatabaseRepository<VorkNode> vorkNodeRepository(RepositoryFactory factory) {
        return factory.create(VorkNode.class);
    }
}
