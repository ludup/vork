package sh.vork.scheduling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.RepositoryFactory;
import sh.vork.scheduling.domain.ScheduledJob;

@Configuration
public class SchedulingRepositoryConfig {

    @Bean
    public DatabaseRepository<ScheduledJob> scheduledJobRepository(RepositoryFactory factory) {
        return factory.create(ScheduledJob.class);
    }
}
