package sh.vork.scheduling.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.jadaptive.orm.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.scheduling.service.AiSchedulerService;

/**
 * Page and REST API controller for the Jobs management UI.
 *
 * <p>All REST endpoints are scoped to the authenticated user — only that user's
 * own jobs are visible or modifiable.
 */
@Controller
public class JobsController {

    private static final Logger log = LoggerFactory.getLogger(JobsController.class);

    private final AiSchedulerService schedulerService;
    private final DatabaseRepository<ScheduledJob> jobRepository;

    public JobsController(AiSchedulerService schedulerService,
                          DatabaseRepository<ScheduledJob> jobRepository) {
        this.schedulerService = schedulerService;
        this.jobRepository = jobRepository;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/jobs")
    public String jobsPage(Model model, @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER jobsPage: [user={}]", user.getUsername());
        model.addAttribute("jobs", schedulerService.listJobsForUser(user.getUsername()));
        model.addAttribute("invocationTypes", InvocationType.values());
        model.addAttribute("durationTypes", DurationType.values());
        return "jobs";
    }

    // ── REST: list ────────────────────────────────────────────────────────────

    @GetMapping("/api/jobs")
    @ResponseBody
    public List<ScheduledJob> listJobs(@AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER listJobs: [user={}]", user.getUsername());
        return schedulerService.listJobsForUser(user.getUsername());
    }

    // ── REST: create ──────────────────────────────────────────────────────────

    @PostMapping("/api/jobs")
    @ResponseBody
    public ResponseEntity<?> createJob(@RequestBody JobRequest req,
                                       @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER createJob: [user={}, name={}]", user.getUsername(), req.name());
        String err = validateRequest(req);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        ScheduledJob job = new ScheduledJob(
                null,
                req.name(),
                req.aiPrompt(),
                null,
                user.getUsername(),
                req.invocationType(),
                parseInstant(req.startTime()),
                req.repeatDuration() > 0 ? req.repeatDuration() : 0,
                req.durationType() != null ? req.durationType() : DurationType.MINUTES,
                0L,
                0L,
                req.agentTemplateId(),
                req.provider(),
                req.modelId(),
                ScheduledJobStatus.WAITING);

        ScheduledJob saved = schedulerService.scheduleJob(job);
        log.info("Job created [id={}, user={}, type={}]", saved.id(), user.getUsername(), saved.invocationType());
        return ResponseEntity.ok(saved);
    }

    // ── REST: update ──────────────────────────────────────────────────────────

    @PutMapping("/api/jobs/{id}")
    @ResponseBody
    public ResponseEntity<?> updateJob(@PathVariable String id,
                                       @RequestBody JobRequest req,
                                       @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER updateJob: [id={}, user={}]", id, user.getUsername());
        ScheduledJob existing = jobRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(existing.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));

        String err = validateRequest(req);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        ScheduledJob updated = new ScheduledJob(
                id,
                req.name(),
                req.aiPrompt(),
                existing.sessionUuid(),
                existing.userId(),
                req.invocationType(),
                parseInstant(req.startTime()),
                req.repeatDuration() > 0 ? req.repeatDuration() : 0,
                req.durationType() != null ? req.durationType() : DurationType.MINUTES,
                existing.lastExecutionTime(),
                existing.nextExecutionTime(),
                req.agentTemplateId(),
                req.provider(),
                req.modelId(),
                existing.status());

        ScheduledJob saved = schedulerService.scheduleJob(updated);
        log.info("Job updated [id={}, user={}]", id, user.getUsername());
        return ResponseEntity.ok(saved);
    }

    // ── REST: delete ──────────────────────────────────────────────────────────

    @DeleteMapping("/api/jobs/{id}")
    @ResponseBody
    public ResponseEntity<?> deleteJob(@PathVariable String id,
                                       @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER deleteJob: [id={}, user={}]", id, user.getUsername());
        ScheduledJob existing = jobRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(existing.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));

        schedulerService.deleteJob(id);
        log.info("Job deleted [id={}, user={}]", id, user.getUsername());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── REST: actions ─────────────────────────────────────────────────────────

    @PostMapping("/api/jobs/{id}/run")
    @ResponseBody
    public ResponseEntity<?> runNow(@PathVariable String id,
                                    @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER runNow: [id={}, user={}]", id, user.getUsername());
        ScheduledJob job = jobRepository.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(job.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));

        schedulerService.runNow(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/jobs/{id}/pause")
    @ResponseBody
    public ResponseEntity<?> pauseJob(@PathVariable String id,
                                      @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER pauseJob: [id={}, user={}]", id, user.getUsername());
        ScheduledJob job = jobRepository.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(job.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        if (job.invocationType() == InvocationType.MANUAL)
            return ResponseEntity.badRequest().body(Map.of("error", "MANUAL jobs cannot be paused."));

        schedulerService.pauseJob(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/jobs/{id}/resume")
    @ResponseBody
    public ResponseEntity<?> resumeJob(@PathVariable String id,
                                       @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER resumeJob: [id={}, user={}]", id, user.getUsername());
        ScheduledJob job = jobRepository.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(job.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));

        schedulerService.resumeJob(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String validateRequest(JobRequest req) {
        if (req.name() == null || req.name().isBlank()) return "Name is required.";
        if (req.aiPrompt() == null || req.aiPrompt().isBlank()) return "AI prompt is required.";
        if (req.invocationType() == null) return "Invocation type is required.";
        if (req.invocationType() == InvocationType.REPEAT && req.repeatDuration() <= 0)
            return "Repeat duration must be greater than zero.";
        return null;
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return Instant.now();
        try {
            // datetime-local input gives "YYYY-MM-DDTHH:mm" — append seconds + Z if needed
            String s = iso.length() == 16 ? iso + ":00Z" : iso.endsWith("Z") ? iso : iso + "Z";
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    record JobRequest(
            String name,
            String aiPrompt,
            InvocationType invocationType,
            String startTime,          // ISO-8601 or datetime-local string
            long repeatDuration,
            DurationType durationType,
            String agentTemplateId,
            String provider,
            String modelId
    ) {}
}
