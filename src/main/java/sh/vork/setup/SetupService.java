package sh.vork.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.security.VorkUser;

/**
 * Detects whether first-time setup is required and handles admin account creation.
 *
 * <p>Setup is required when no {@link VorkUser} documents exist in the database.
 * After the first user is created the result is cached in a volatile flag so
 * subsequent requests never hit the database for this check.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final DatabaseRepository<VorkUser> userRepo;
    private final PasswordEncoder passwordEncoder;

    /** Cached flag — flipped to {@code true} once setup is confirmed complete. */
    private volatile boolean setupComplete = false;

    public SetupService(DatabaseRepository<VorkUser> userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Returns {@code true} when no admin accounts exist and setup must be run.
     * The result is cached after setup completes so subsequent checks are free.
     */
    public boolean isSetupRequired() {
        if (setupComplete) return false;
        try {
            boolean required = userRepo.count() == 0;
            if (!required) setupComplete = true; // cache once at least one user exists
            return required;
        } catch (Exception e) {
            log.warn("Setup check failed, assuming complete: {}", e.getMessage());
            return false; // don't block startup if DB is briefly unavailable
        }
    }

    /**
     * Creates the initial admin user with the given credentials.
     *
     * @param username chosen admin username
     * @param password plain-text password (will be BCrypt-encoded)
     * @throws IllegalArgumentException if a user with that username already exists
     */
    public void createAdminUser(String username, String password) {
        if (userRepo.get(username) != null) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }
        long now = System.currentTimeMillis();
        VorkUser admin = new VorkUser(username, passwordEncoder.encode(password), "ADMIN", now, now);
        userRepo.save(admin);
        setupComplete = true;
        log.info("Admin user created during setup: [username={}]", username);
    }
}
