package com.aegis.ai;

import com.aegis.workitem.WorkItem;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Wraps the unreliable LLM call in the resilience patterns a reliability team builds:
 *
 *  - @Retry:          retries transient failures with backoff + jitter (config
 *                     in application.yml). Safe only because triage is read-only
 *                     / idempotent for a given work item.
 *  - @CircuitBreaker: after a failure threshold, the breaker OPENS and calls
 *                     fail fast (no waiting on a dead dependency), then probes
 *                     in HALF_OPEN before closing again. This stops a slow LLM
 *                     from exhausting our threads and cascading into an outage.
 *  - fallback:        graceful degradation -- if the LLM is unavailable we still
 *                     return a useful heuristic triage instead of a 500.
 *
 * The time limiter / bulkhead are configured in application.yml as well.
 */
@Service
public class TriageService {

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);

    private final LlmClient llm;

    public TriageService(LlmClient llm) {
        this.llm = llm;
    }

    @CircuitBreaker(name = "llm", fallbackMethod = "fallbackTriage")
    @Retry(name = "llm")
    public TriageResult triage(WorkItem item) {
        String prompt = """
                You are a work-management triage assistant. Given a work item,
                respond in two short lines:
                1) RISK: low|medium|high
                2) NEXT: the single most useful next action.

                Title: %s
                Status: %s
                Priority: %s
                Description: %s
                """.formatted(item.title(), item.status(), item.priority(), item.description());

        String text = llm.summarizeTriage(prompt);
        return new TriageResult(item.id(), text.strip(), "llm");
    }

    /**
     * Fallback invoked by the circuit breaker. Must share the method signature
     * plus a trailing Throwable. Returns a deterministic heuristic so the
     * endpoint degrades gracefully rather than failing.
     */
    @SuppressWarnings("unused")
    TriageResult fallbackTriage(WorkItem item, Throwable t) {
        log.warn("LLM triage unavailable for item {} ({}). Serving heuristic fallback.",
                item.id(), t.toString());

        String risk = switch (item.priority() == null ? "" : item.priority().toLowerCase()) {
            case "high", "urgent" -> "high";
            case "medium" -> "medium";
            default -> "low";
        };
        String next = "open".equalsIgnoreCase(item.status())
                ? "Assign an owner and set a due date."
                : "Review current status and confirm it is on track.";

        String body = "RISK: " + risk + "\nNEXT: " + next + "\n(heuristic fallback)";
        return new TriageResult(item.id(), body, "fallback");
    }

    public record TriageResult(long workItemId, String triage, String source) {}
}
