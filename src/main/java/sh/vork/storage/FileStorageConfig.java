package sh.vork.storage;

import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.RepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link DatabaseRepository}{@code <StoredFile>} Spring bean.
 */
@Configuration
public class FileStorageConfig {

    @Bean
    public DatabaseRepository<StoredFile> storedFileRepository(RepositoryFactory factory) {
        return factory.create(StoredFile.class);
    }
}
