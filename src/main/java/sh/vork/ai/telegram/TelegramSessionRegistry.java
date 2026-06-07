package sh.vork.ai.telegram;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.service.ChatService;
import sh.vork.setup.SystemSettings;
import sh.vork.setup.SystemSettingsService;

/**
 * In-memory registry that maps a Telegram {@code chatId} to the active AI session UUID.
 *
 * <p>Sessions are created on first contact and discarded when the user sends {@code /new}.
 * The registry is in-memory only; a server restart begins a new session automatically.
 */
@Service
public class TelegramSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(TelegramSessionRegistry.class);

    /** chatId -> sessionUuid */
    private final ConcurrentHashMap<String, String> activeSessions = new ConcurrentHashMap<>();

    private final ChatService                       chatService;
    private final SystemSettingsService             systemSettingsService;

    public TelegramSessionRegistry(ChatService chatService,
                                    SystemSettingsService systemSettingsService) {
        this.chatService = chatService;
        this.systemSettingsService = systemSettingsService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the session UUID for the given chat, creating a new session if none exists.
     *
     * @param username  authenticated Vork username
     * @param configId  NotificationProviderConfig UUID (which bot)
     * @param chatId    Telegram chat ID
     * @param botToken  bot token (stored in session env for replies)
     * @return session UUID (never null)
     */
    public String getOrCreate(String username, String configId, String chatId, String botToken) {
        return activeSessions.computeIfAbsent(chatId,
                id -> createSession(username, configId, chatId, botToken));
    }

    /**
     * Returns the active session UUID for the given chat, or {@code null} if none is tracked.
     */
    public String get(String chatId) {
        return activeSessions.get(chatId);
    }

    /**
     * Removes the active session mapping for the given chat.
     * The next message from this chat will create a fresh session.
     */
    public void reset(String chatId) {
        String removed = activeSessions.remove(chatId);
        log.info("Telegram session reset [chatId={}, removedSession={}]", chatId, removed);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String createSession(String username, String configId, String chatId, String botToken) {
        String providerName = resolveDefaultProviderName();
        AiSession session = chatService.createTelegramSession(username, configId, chatId, botToken, providerName);
        log.info("Telegram session created [chatId={}, sessionUuid={}, user={}, provider={}]",
                chatId, session.uuid(), username, providerName);
        return session.uuid();
    }

    private String resolveDefaultProviderName() {
        SystemSettings gs = systemSettingsService.getGlobal();
        if (gs != null && gs.defaultProvider() != null && !gs.defaultProvider().isBlank()) {
            return gs.defaultProvider();
        }
        return AiProvider.GEMINI.name(); // last resort
    }
}
