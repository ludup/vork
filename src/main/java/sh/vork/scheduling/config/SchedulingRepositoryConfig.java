package sh.vork.scheduling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;
import sh.vork.scheduling.domain.ScheduledJob;

@Configuration
public class SchedulingRepositoryConfig {

    @Bean
    public DatabaseRepository<ScheduledJob> scheduledJobRepository(DatabaseRepositoryFactory factory) {
        return factory.create(ScheduledJob.class);
    }
}
