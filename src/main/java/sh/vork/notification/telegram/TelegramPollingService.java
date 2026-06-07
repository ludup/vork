package sh.vork.notification.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadaptive.orm.DatabaseRepository;

import sh.vork.notification.NotificationProviderConfig;

/**
 * Runs one long-poll loop per configured Telegram bot and dispatches every
 * received message to all registered {@link TelegramMessageConsumer} beans.
 *
 * <p>A new virtual thread is started for each active bot.  Threads honour
 * {@link Thread#interrupt()} and clean up their entry from the active-pollers
 * map when they exit.
 *
 * <p>Long-poll timeout is 30 seconds; the HTTP client timeout is set to
 * 40 seconds to give Telegram's server a 10-second buffer before Java
 * considers the connection stale.
 */
@Service
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);
    private static final String API_BASE = "https://api.telegram.org/bot";

    /** configId → running virtual thread */
    private final ConcurrentHashMap<String, Thread> activePollers = new ConcurrentHashMap<>();
    /** configId → next expected update-id offset */
    private final ConcurrentHashMap<String, AtomicInteger> offsets = new ConcurrentHashMap<>();

    private final DatabaseRepository<NotificationProviderConfig> configRepo;
    private final List<TelegramMessageConsumer> consumers;
    private final ObjectMapper objectMapper;
    private final TelegramApiClient telegramApiClient;

    public TelegramPollingService(DatabaseRepository<NotificationProviderConfig> configRepo,
                                   List<TelegramMessageConsumer> consumers,
                                   ObjectMapper objectMapper,
                                   TelegramApiClient telegramApiClient) {
        this.configRepo         = configRepo;
        this.consumers          = consumers;
        this.objectMapper       = objectMapper;
        this.telegramApiClient  = telegramApiClient;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.debug("ENTER onStartup: scanning Telegram configs");
        try (var stream = configRepo.list(0, Integer.MAX_VALUE)) {
            stream.filter(c -> "telegram".equals(c.providerKey()))
                  .forEach(this::startPolling);
        }
        log.debug("EXIT onStartup");
    }

    /** Starts a poll loop for the given config if one is not already running. */
    public synchronized void startPolling(NotificationProviderConfig config) {
        if (activePollers.containsKey(config.uuid())) {
            log.debug("Telegram poller already active [configId={}]", config.uuid());
            return;
        }
        offsets.put(config.uuid(), new AtomicInteger(0));
        Thread t = Thread.ofVirtual()
                .name("telegram-poll-" + config.uuid())
                .start(() -> pollLoop(config));
        activePollers.put(config.uuid(), t);
        log.info("Telegram long-poll started [configId={}, displayName={}]",
                config.uuid(), config.displayName());
    }

    /** Interrupts and removes the poll loop for the given config ID. */
    public synchronized void stopPolling(String configId) {
        Thread t = activePollers.remove(configId);
        offsets.remove(configId);
        if (t != null) {
            t.interrupt();
            log.info("Telegram long-poll stopped [configId={}]", configId);
        }
    }

    // ── Poll loop ─────────────────────────────────────────────────────────────

    private void pollLoop(NotificationProviderConfig config) {
        String botToken = config.settings().getOrDefault("botToken", "");
        AtomicInteger offset = offsets.get(config.uuid());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.debug("ENTER pollLoop: [configId={}]", config.uuid());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String url = API_BASE + botToken + "/getUpdates"
                        + "?offset=" + offset.get() + "&timeout=30";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(40))
                        .GET().build();

                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    processBody(config.uuid(), botToken, response.body(), offset);
                } else {
                    log.warn("Telegram getUpdates non-2xx [status={}, configId={}]",
                            response.statusCode(), config.uuid());
                    Thread.sleep(5_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Telegram poll error [configId={}, error={}]",
                        config.uuid(), e.getMessage());
                try { Thread.sleep(5_000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        activePollers.remove(config.uuid());
        log.info("EXIT pollLoop: [configId={}]", config.uuid());
    }

    private void processBody(String configId, String botToken,
                              String body, AtomicInteger offset) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("ok").asBoolean()) {
                log.warn("Telegram API ok=false [configId={}]", configId);
                return;
            }
            JsonNode results = root.path("result");
            if (!results.isArray() || results.isEmpty()) return;

            for (JsonNode upd : results) {
                int updateId = upd.path("update_id").asInt();

                // ── callback_query (inline keyboard button press) ──────────────
                JsonNode cbNode = upd.path("callback_query");
                if (!cbNode.isMissingNode() && !cbNode.isNull()) {
                    String callbackQueryId = cbNode.path("id").asText();
                    String callbackData    = cbNode.path("data").asText(null);
                    JsonNode cbFrom        = cbNode.path("from");
                    JsonNode cbMsg         = cbNode.path("message");
                    String chatId          = cbMsg.path("chat").path("id").asText(
                                                cbFrom.path("id").asText(""));
                    String firstName       = cbFrom.path("first_name").asText("");
                    String username        = cbFrom.path("username").asText("");

                    // Acknowledge immediately to clear loading state
                    telegramApiClient.answerCallbackQuery(botToken, callbackQueryId, null);

                    TelegramMessageConsumer.IncomingMessage msg =
                            new TelegramMessageConsumer.IncomingMessage(
                                    configId, botToken, chatId, firstName, username,
                                    null, updateId, callbackQueryId, callbackData);
                    dispatch(msg);
                    offset.accumulateAndGet(updateId + 1, Math::max);
                    continue;
                }

                // ── regular text message ───────────────────────────────────────
                JsonNode msgNode = upd.path("message");
                if (msgNode.isMissingNode() || msgNode.isNull()) {
                    offset.accumulateAndGet(updateId + 1, Math::max);
                    continue;
                }

                JsonNode fromNode = msgNode.path("from");
                JsonNode chatNode = msgNode.path("chat");

                String chatId    = chatNode.path("id").asText();
                String text      = msgNode.path("text").asText(null);
                String firstName = (!fromNode.isMissingNode()
                        ? fromNode : chatNode).path("first_name").asText("");
                String username  = (!fromNode.isMissingNode()
                        ? fromNode : chatNode).path("username").asText("");

                TelegramMessageConsumer.IncomingMessage msg =
                        new TelegramMessageConsumer.IncomingMessage(
                                configId, botToken, chatId, firstName, username,
                                text, updateId, null, null);
                dispatch(msg);
                offset.accumulateAndGet(updateId + 1, Math::max);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Telegram updates [error={}]", e.getMessage());
        }
    }

    private void dispatch(TelegramMessageConsumer.IncomingMessage msg) {
        for (TelegramMessageConsumer consumer : consumers) {
            try {
                if (consumer.accepts(msg)) consumer.process(msg);
            } catch (Exception e) {
                log.warn("Consumer error [consumer={}, error={}]",
                        consumer.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}

