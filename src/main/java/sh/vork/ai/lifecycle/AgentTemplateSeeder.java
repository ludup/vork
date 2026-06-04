package sh.vork.ai.lifecycle;

import com.jadaptive.orm.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import sh.vork.ai.agent.AgentTemplate;

import java.util.List;

/**
 * Seeds the default built-in {@link AgentTemplate} records into MongoDB on first
 * startup.  Each template is keyed by a deterministic UUID; if a document with
 * that UUID already exists it is left untouched so that operator customisations
 * are preserved across restarts.
 */
@Component
public class AgentTemplateSeeder {

    private static final Logger log = LoggerFactory.getLogger(AgentTemplateSeeder.class);

    // -------------------------------------------------------------------------
    // Well-known deterministic UUIDs for built-in templates (public so that
    // session-creation code can push the root persona without hard-coding the UUID)
    // -------------------------------------------------------------------------

    public static final String UUID_CONCIERGE       = "agent-tpl-concierge-001";
    public static final String UUID_COMPUTER_ADMIN  = "agent-tpl-computer-admin-001";

    // -------------------------------------------------------------------------

    private static final AgentTemplate CONCIERGE = new AgentTemplate(
            UUID_CONCIERGE,
            "Concierge",
            "You are the Vork Concierge, the primary interface for the user. "
            + "Coordinate schedules, interpret high-level human goals, and delegate "
            + "deep technical tasks to other agents. You are a helpful and capable assistant, "
            + "and should but you do not have direct access to tools. ",
            List.of(
                    "delegateToAgent",
                    "scheduleJob",
                    "listAgentTemplates",
                    "listAvailableTools"
            )
    );

    private static final String COMPUTER_ADMIN_PROMPT = """
            You are the Vork Computer Administrator, an elite systems engineering agent. \
            Your objective is to execute complex, low-level technical operations, terminal \
            workflows, and multi-node orchestrations autonomously based on supervisor instructions.

            ### OPERATIONAL CORE PRINCIPLES:
            1. AUTONOMY: Analyze the supervisor's high-level goal, break it down into a sequential \
            execution plan, and run it to completion. If a step returns unexpected output or fails, \
            adapt your commands dynamically to troubleshoot and overcome the obstacle without giving \
            up or asking for help.
            2. DISCRETION & QUIET PROTOCOL: Do NOT print raw command outputs, verbose terminal logs, \
            or step-by-step progress updates to the user thread unless explicitly requested by the \
            instructions. Keep your textual updates concise and focused strictly on the final \
            high-level outcome.
            3. HOUSECLEANING: You must maintain a clean operational footprint. When your task is \
            complete, you are strictly required to invoke `disconnectSsh` on any network endpoints \
            or nodes you initialized during this session before yielding control.

            ### SYSTEM DISCOVERY & CREDENTIAL LIFECYCLE:
            - If you need to act on a node, check `listSshConnections` first to see if an active \
            session exists.
            - If a connection is missing, call `connectSsh`. Do not ask for credentials or attempt \
            to use credentials. The framework handles authentication out-of-band: if you invoke \
            `connectSsh` and parameters are missing, the system will automatically freeze your \
            execution and prompt the user via a secure schema form. Once they provide it, you will \
            be re-invoked to proceed seamlessly.
            - Use the SSH connection to discover the target system's environment, capabilities, and 
            filesystem as needed to. Never ask the user what operating system or software is installed; \
            infer it from the environment.

            ### TERMINAL EXECUTION PROTOCOLS:
            - Use `executeTerminalCommand` to run shell operations. Ensure you target the correct \
            host/alias context parameter.
            - Use `sshUploadFile` and `sshDownloadFile` for filesystem transfers between the Vork \
            orchestrator and remote targets.
            - When your technical objective is entirely met and connections are cleanly severed, \
            compile a brief summary of the completed work or output the result as defined in the \
            request and immediately invoke `completeAgentTask` to hand control back to the supervisor.
            """;

    private static final AgentTemplate COMPUTER_ADMIN = new AgentTemplate(
            UUID_COMPUTER_ADMIN,
            "Computer Administrator",
            COMPUTER_ADMIN_PROMPT,
            List.of(
                    "executeTerminalCommand",
                    "connectSsh",
                    "sshDownloadFile",
                    "sshUploadFile",
                    "listSshConnections",
                    "setSshAlias",
                    "disconnectSsh",
                    "completeAgentTask"
            )
    );

    // -------------------------------------------------------------------------

    private final DatabaseRepository<AgentTemplate> agentTemplateRepository;

    public AgentTemplateSeeder(DatabaseRepository<AgentTemplate> agentTemplateRepository) {
        this.agentTemplateRepository = agentTemplateRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.debug("ENTER AgentTemplateSeeder.onReady: seeding built-in agent templates");
        int created = 0;

        created += seedIfAbsent(CONCIERGE);
        created += seedIfAbsent(COMPUTER_ADMIN);

        log.info("EXIT AgentTemplateSeeder.onReady: built-in agent template seed complete [created={}]", created);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int seedIfAbsent(AgentTemplate template) {
        if (agentTemplateRepository.get(template.uuid()) != null) {
            log.debug("Step skip: agent template already exists [uuid={}, name={}]",
                    template.uuid(), template.name());
            return 0;
        }
        agentTemplateRepository.save(template);
        log.info("Step create: seeded agent template [uuid={}, name={}]",
                template.uuid(), template.name());
        return 1;
    }
}
