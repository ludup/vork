package sh.vork.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.config.AiConfig;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.AiProvider;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.provider.AiChatClientFactory;
import sh.vork.ai.session.SessionToolStore;

/**
 * Routes AI generation requests to the appropriate {@link ChatClient} at runtime.
 *
 * <h3>Dynamic routing</h3>
 * The injected {@code Map<AiProvider, ChatClient>} is the single source of truth
 * for which backend backs which enum value.  Adding a new provider only requires
 * updating {@code AiConfig} — this class never changes.
 *
 * <h3>The {@code mutate()} pattern</h3>
 * Each call goes through {@link ChatClient#mutate()} which returns a fresh
 * {@link ChatClient.Builder} pre-seeded with the shared client's configuration
 * (default functions, options, system prompt, etc.).  Building a new instance
 * from that builder gives a per-request {@link ChatClient} with an isolated
 * call chain, so:
 * <ul>
 *   <li>The shared base client is never modified between concurrent calls.</li>
 *   <li>Per-request overrides (extra system instructions, option tweaks, additional
 *       tools) can be applied to the mutated builder before building, without
 *       leaking to other in-flight calls.</li>
 * </ul>
 */
@Service
public class AiOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestrationService.class);
        private static final String BACKGROUND_OPERATIONAL_PROTOCOL = """
BACKGROUND OPERATIONAL PROTOCOL: You are executing autonomously in an isolated background thread. You must perform all necessary analysis and tool calls across multiple message rounds without expecting further human input. Once you have validated that the requested objective is entirely satisfied (e.g., your types compile successfully and records are saved), you MUST invoke the completeBackgroundTask tool to cleanly finalize the run. Do not exit without invoking this tool.
                        """.stripIndent();

        private static final String STRUCTURED_RESPONSE_MANDATE = """

            ### CORE OUTPUT REQUIREMENT
            You MUST return your output as a single valid JSON object matching the StructuredAgentResponse schema.
            No markdown fences, no explanation outside the JSON. Your entire response must be parsable JSON:
            {
              "status": "FINISHED_TURN | DELEGATE_TURN | CONTINUE_TURN | SWITCH_AGENT",
              "textResponse": "<your human-readable message to the user or supervisor>",
              "targetAgent": "<exact agent display name, or null>",
              "delegationInstructions": "<full self-contained task for the sub-agent, or null>"
            }
            1. If your goal is completed or you are returning a result to a supervisor, set status to "FINISHED_TURN".
            2. If you need to delegate a job to a specialized expert agent, set status to "DELEGATE_TURN",
               populate "targetAgent" with their exact display name, and write explicit, comprehensive
               task parameters inside "delegationInstructions".
            3. If you have made meaningful progress and want to inform the user before continuing execution,
               set status to "CONTINUE_TURN". Your textResponse will be shown to the user immediately and
               you will be invoked again automatically — do NOT stop and wait for a user reply.
            4. If the user explicitly asks to switch to a different agent, set status to "SWITCH_AGENT",
               set "targetAgent" to the exact display name of the desired agent, and write a brief
               confirmation message in "textResponse". The session active agent will be updated and the
               user will see a confirmation — you do NOT need to do any work for the new agent.
            """.stripIndent();

    /**
     * Stable model alias to fall back to when the session's requested model is
     * deprecated, removed (404/400), or otherwise unavailable.  These should be
     * long-lived, generally-available model names for each provider.
     */
    private static final Map<AiProvider, String> STABLE_FALLBACK_MODELS = Map.of(
            AiProvider.GEMINI,               "gemini-2.5-flash",
            AiProvider.OPENAI,               "gpt-4o",
            AiProvider.OLLAMA,               "llama3.2",
            AiProvider.BACKGROUND_SCHEDULER, "gemini-2.5-flash"
    );

        private final Map<AiProvider, ChatClient> registry;
        private final AiChatClientFactory chatClientFactory;
        private final SessionEnvironmentService sessionEnvironmentService;
        private final DatabaseRepository<AiSession> sessionRepo;
        private final DatabaseRepository<AgentTemplate> agentTemplateRepo;
        private final Map<String, ToolCallback> securedToolCallbackMap;
        private final SessionToolStore sessionToolStore;

        public AiOrchestrationService(Map<AiProvider, ChatClient> chatClientRegistry,
                                                                  AiChatClientFactory chatClientFactory,
                                                                  SessionEnvironmentService sessionEnvironmentService,
                                                                  DatabaseRepository<AiSession> aiSessionRepository,
                                                                  DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                                                  Map<String, ToolCallback> securedToolCallbackMap,
                                                                  SessionToolStore sessionToolStore) {
                this.registry = chatClientRegistry;
                this.chatClientFactory = chatClientFactory;
                this.sessionEnvironmentService = sessionEnvironmentService;
                this.sessionRepo = aiSessionRepository;
                this.agentTemplateRepo = agentTemplateRepository;
                this.securedToolCallbackMap = securedToolCallbackMap;
                this.sessionToolStore = sessionToolStore;
    }

    /**
     * Generates a text response for {@code userPrompt} using the specified provider.
     *
     * @param userPrompt the user's prompt text
     * @param provider   the AI backend to route to
     * @return the model's response as a plain string
     * @throws IllegalArgumentException if the provider has no registered client
     */
    public String generate(String userPrompt, AiProvider provider) {
        ChatClient base = resolveClient(provider);

        // mutate() seeds a fresh builder from the shared client's config so
        // per-request changes (e.g. additional tools, system prompt override)
        // never bleed into other concurrent calls.
        log.info("Generating response [provider={}] prompt=\"{}\"...",
                provider, userPrompt.length() > 120 ? userPrompt.substring(0, 120) + "…" : userPrompt);

        String effectiveText = withBackgroundDirective(userPrompt, provider);
        String response = callWithFallback(
                builder -> builder.build().prompt().user(effectiveText).call().content(),
                base, provider);

        log.info("Response received [provider={}, length={}]: {}",
                provider,
                response == null ? 0 : response.length(),
                response == null ? "<null>" : (response.length() > 200 ? response.substring(0, 200) + "…" : response));

        return response;
    }

    /**
     * Generates a response using prior conversation history for context.
     *
     * @param conversationHistory previous turns as Spring AI {@link Message} objects
     * @param newUserMessage      the latest user input
     * @param provider            the AI backend to route to
     * @return the model's response as a plain string
     */
    public String generateWithHistory(List<Message> conversationHistory, String newUserMessage, AiProvider provider) {
        ChatClient base = resolveClient(provider);

        log.info("Generating chat response [provider={}, history={} msgs]...", provider, conversationHistory.size());

        Message[] historyArray = conversationHistory.toArray(Message[]::new);
        String effectiveUser   = withBackgroundDirective(newUserMessage, provider);
        String response = callWithFallback(
                builder -> builder.build().prompt().messages(historyArray).user(effectiveUser).call().content(),
                base, provider);

        log.info("Chat response received [provider={}, length={}]",
                provider, response == null ? 0 : response.length());

        return response;
    }

    /**
     * Generates a response with conversation history and media attachments.
     *
     * <p>The {@code media} list is attached to the current user turn so that
     * vision / multimodal models can reason over the provided files.  Pass an
     * empty list (never {@code null}) when there are no attachments.
     *
     * @param conversationHistory previous turns
     * @param userText            the user's text message (may be blank if only media)
     * @param media               Spring AI {@link Media} objects to attach
     * @param provider            the AI backend to route to
     * @return the model's response as a plain string
     */
    public String generateWithHistoryAndMedia(List<Message> conversationHistory,
                                              String userText,
                                              List<Media> media,
                                              AiProvider provider) {
        ChatClient base = resolveClient(provider);

        log.info("Generating chat response with media [provider={}, history={} msgs, media={}]",
                provider, conversationHistory.size(), media.size());

        List<Message> allMessages = new ArrayList<>(conversationHistory);
        String effectiveText = (userText == null || userText.isBlank()) ? "Please analyse the attached file(s)." : userText;
        effectiveText = withBackgroundDirective(effectiveText, provider);
        allMessages.add(UserMessage.builder().text(effectiveText).media(media).build());

        Message[] allMsgsArray = allMessages.toArray(Message[]::new);
        String response = callWithFallback(
                builder -> builder.build().prompt().messages(allMsgsArray).call().content(),
                base, provider);

        log.info("Chat response with media received [provider={}, length={}]",
                provider, response == null ? 0 : response.length());

        return response;
    }

        private static String withBackgroundDirective(String text, AiProvider provider) {
                String baseText = text == null ? "" : text;
                if (provider != AiProvider.BACKGROUND_SCHEDULER) {
                        return baseText;
                }
                return BACKGROUND_OPERATIONAL_PROTOCOL + "\n\n" + baseText;
        }

        private String composeSystemPrompt() {
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                StringBuilder prompt = new StringBuilder(AiConfig.BASE_SYSTEM_PROMPT);

                if (sessionUuid == null || sessionUuid.isBlank()) {
                        return prompt.toString();
                }

                // Inject active agent template directives
                AiSession session = sessionRepo.get(sessionUuid);
                if (session != null) {
                        String agentId = session.getActiveAgentTemplateId();
                        if (agentId != null) {
                                AgentTemplate template = agentTemplateRepo.get(agentId);
                                if (template != null && !template.systemPrompt().isBlank()) {
                                        prompt.append("\n\n### ACTIVE AGENT DIRECTIVES\n").append(template.systemPrompt());
                                }
                        }
                }

                // Inject session environment variables
                Map<String, String> envMap = sessionEnvironmentService.getEnv(sessionUuid);
                if (envMap != null && !envMap.isEmpty()) {
                        StringBuilder envBlock = new StringBuilder("\n### ACTIVE SESSION ENVIRONMENT VARIABLES\n");
                        envMap.forEach((k, v) -> envBlock.append(k).append("=").append(v).append("\n"));
                        prompt.append(envBlock);
                }

                // Mandate structured output for interactive (non-background) sessions
                if (session != null && session.originMode() != SessionOriginMode.BACKGROUND) {
                        prompt.append(STRUCTURED_RESPONSE_MANDATE);
                }

                return prompt.toString();
        }

        /**
         * Resolves the {@link ChatClient} for the given provider, falling back to the
         * factory for dynamically-configured providers (OpenAI, Ollama) not in the static registry.
         */
        private ChatClient resolveClient(AiProvider provider) {
                ChatClient base = registry.get(provider);
                if (base == null) {
                        base = chatClientFactory.getBaseClient(provider);
                }
                if (base == null) {
                        throw new IllegalArgumentException(
                                "No ChatClient configured for provider: " + provider
                                + ". Configure credentials in Settings → AI Models.");
                }
                return base;
        }

        /**
         * Returns the model ID stored on the active session, or {@code null} if none set.
         */
        private String resolveSessionModelId() {
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                if (sessionUuid == null || sessionUuid.isBlank()) return null;
                AiSession session = sessionRepo.get(sessionUuid);
                return session != null ? session.modelId() : null;
        }

        /**
         * Builds a mutated {@link ChatClient.Builder} from the shared base client with the
         * composed system prompt and, when an active {@link AgentTemplate} restricts the
         * allowed tools, the filtered tool set applied.  If the session has a specific model
         * override, it is applied as a default option on the builder.
         */
        private ChatClient.Builder buildMutatedClient(ChatClient base) {
                return buildMutatedClientInternal(base, null);
        }

        /**
         * Same as {@link #buildMutatedClient(ChatClient)} but forces a specific {@code modelId},
         * ignoring the session's stored model.  Used by the deprecation fallback path.
         */
        private ChatClient.Builder buildMutatedClientWithModel(ChatClient base, String forcedModel) {
                return buildMutatedClientInternal(base, forcedModel);
        }

        private ChatClient.Builder buildMutatedClientInternal(ChatClient base, String forcedModel) {
                // Resolve which tools to expose for this request: filtered subset when an
                // AgentTemplate is active, or the full secured set otherwise.
                // Tools are always set here (never on the base ChatClient) to prevent
                // Spring AI from seeing duplicates when the builder is mutated.
                ToolCallback[] filtered = resolveFilteredToolCallbacks();
                ToolCallback[] tools = (filtered != null)
                        ? filtered
                        : securedToolCallbackMap.values().toArray(ToolCallback[]::new);

                // Merge session-scoped tools (e.g. completeBackgroundTask for background sessions).
                // These are hidden from the global registry and injected programmatically per session.
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                List<ToolCallback> sessionTools = sessionToolStore.getTools(sessionUuid);
                if (!sessionTools.isEmpty()) {
                        List<ToolCallback> merged = new ArrayList<>(List.of(tools));
                        merged.addAll(sessionTools);
                        tools = merged.toArray(ToolCallback[]::new);
                        log.debug("Merged {} session-scoped tool(s) [session={}]", sessionTools.size(), sessionUuid);
                }

                ChatClient.Builder builder = base.mutate()
                        .defaultSystem(composeSystemPrompt())
                        .defaultToolCallbacks(tools);

                String modelId = (forcedModel != null) ? forcedModel : resolveSessionModelId();
                if (modelId != null && !modelId.isBlank()) {
                        log.debug("Applying model override: {}", modelId);
                        builder.defaultOptions(ChatOptions.builder().model(modelId).build());
                }

                return builder;
        }

        /**
         * Executes {@code callFn} against a mutated client, and on a deprecation/not-found
         * error automatically retries once with the provider's stable fallback model.
         *
         * <p>Triggers on exceptions whose message chain contains {@code 404}, {@code 400},
         * {@code "no longer available"}, {@code "deprecated"}, {@code "not found"}, or
         * Google's {@code "INVALID_ARGUMENT"} status code.
         */
        private String callWithFallback(Function<ChatClient.Builder, String> callFn,
                                        ChatClient base,
                                        AiProvider provider) {
                try {
                        return callFn.apply(buildMutatedClient(base));
                } catch (RuntimeException e) {
                        if (!isDeprecatedModelError(e)) throw e;
                        String fallback = STABLE_FALLBACK_MODELS.getOrDefault(provider, "");
                        log.warn("Model unavailable/deprecated for provider {}; retrying with fallback model \"{}\" (original error: {})",
                                provider, fallback, e.getMessage());
                        if (fallback.isBlank()) throw e;
                        return callFn.apply(buildMutatedClientWithModel(base, fallback));
                }
        }

        /** Returns {@code true} when the exception looks like a deprecated/removed-model error. */
        private static boolean isDeprecatedModelError(RuntimeException e) {
                String msg = collectExceptionMessages(e);
                return msg.contains("404")
                        || msg.contains("400")
                        || containsIgnoreCase(msg, "no longer available")
                        || containsIgnoreCase(msg, "deprecated")
                        || containsIgnoreCase(msg, "not found")
                        || containsIgnoreCase(msg, "INVALID_ARGUMENT");
        }

        private static String collectExceptionMessages(Throwable t) {
                StringBuilder sb = new StringBuilder();
                while (t != null) {
                        if (t.getMessage() != null) sb.append(t.getMessage()).append(' ');
                        t = t.getCause();
                }
                return sb.toString();
        }

        private static boolean containsIgnoreCase(String text, String search) {
                return text.toLowerCase().contains(search.toLowerCase());
        }

        /**
         * Returns a filtered array of tool callbacks for the active agent template, or
         * {@code null} if no filtering is needed (i.e., the default tool set should be used).
         */
        private ToolCallback[] resolveFilteredToolCallbacks() {
                String sessionUuid = ToolExecutionContext.getSessionUuid();
                if (sessionUuid == null || sessionUuid.isBlank()) {
                        return null;
                }

                AiSession session = sessionRepo.get(sessionUuid);
                if (session == null) {
                        return null;
                }

                String agentId = session.getActiveAgentTemplateId();
                if (agentId == null) {
                        return null;
                }

                AgentTemplate template = agentTemplateRepo.get(agentId);
                if (template == null || template.allowedTools() == null || template.allowedTools().isEmpty()) {
                        return null;
                }

                ToolCallback[] result = template.allowedTools().stream()
                        .map(securedToolCallbackMap::get)
                        .filter(Objects::nonNull)
                        .toArray(ToolCallback[]::new);

                log.debug("Tool filtering active [agent={}, allowedTools={}, resolved={}]",
                        agentId, template.allowedTools().size(), result.length);

                return result.length > 0 ? result : null;
        }

}
