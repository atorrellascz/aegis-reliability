package com.aegis.workitem;

import com.aegis.ai.TriageService;
import com.aegis.cache.CacheAsideService;
import com.aegis.idempotency.IdempotencyStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints:
 *   GET  /api/v1/work-items/{id}          -> cached read (cache-aside)
 *   POST /api/v1/work-items/{id}/triage   -> AI triage (rate-limited + circuit-broken)
 *
 * The triage endpoint honours an Idempotency-Key header: replaying the same key
 * returns the stored result instead of re-calling the LLM. This matters because
 * AI agents retry aggressively; idempotency is what makes those retries safe and
 * cheap. The store is Redis-backed (see IdempotencyStore) so it survives a
 * restart and is shared across instances.
 */
@RestController
@RequestMapping("/api/v1/work-items")
public class WorkItemController {

    private final WorkItemRepository repository;
    private final CacheAsideService cache;
    private final TriageService triageService;
    private final MeterRegistry metrics;

    private final IdempotencyStore idempotency;

    public WorkItemController(WorkItemRepository repository,
                              CacheAsideService cache,
                              TriageService triageService,
                              IdempotencyStore idempotency,
                              MeterRegistry metrics) {
        this.repository = repository;
        this.cache = cache;
        this.triageService = triageService;
        this.idempotency = idempotency;
        this.metrics = metrics;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getWorkItem(@PathVariable long id) {
        // Demonstrates cache-aside: the title is cached; the full object would be
        // serialized in a real impl. Here we just prove the read path is cached.
        String cachedTitle = cache.getOrLoad(
                "wi:" + id,
                Duration.ofSeconds(30),
                () -> repository.findById(id).map(WorkItem::title).orElse("__missing__"));

        if ("__missing__".equals(cachedTitle)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", id, "title", cachedTitle));
    }

    @PostMapping("/{id}/triage")
    public ResponseEntity<?> triage(@PathVariable long id,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {        

        if (idemKey != null) {
            Optional<TriageService.TriageResult> replay = idempotency.find(idemKey);
            if (replay.isPresent()) {
                return ResponseEntity.ok(replay.get());
            }
        }

        Optional<WorkItem> item = repository.findById(id);
        if (item.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Timer.Sample sample = Timer.start(metrics);
        TriageService.TriageResult result = triageService.triage(item.get());
        sample.stop(metrics.timer("aegis.triage.duration", "source", result.source()));
        metrics.counter("aegis.triage.requests", "source", result.source()).increment();

        if (idemKey != null) {
            idempotency.save(idemKey, result);
        }
        return ResponseEntity.ok(result);
    }
}
