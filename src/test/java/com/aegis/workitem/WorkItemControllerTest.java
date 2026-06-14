package com.aegis.workitem;

import com.aegis.ai.TriageService;
import com.aegis.ai.TriageService.TriageResult;
import com.aegis.cache.CacheAsideService;
import com.aegis.idempotency.IdempotencyStore;
import com.aegis.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test for the triage endpoint. Only the controller is loaded;
 * every collaborator is mocked, so there is no Redis, Postgres or LLM involved.
 * Filters are disabled so the rate limiter does not interfere with these tests.
 */
@WebMvcTest(WorkItemController.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkItemControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean WorkItemRepository repository;
    @MockBean CacheAsideService cache;
    @MockBean TriageService triageService;
    @MockBean IdempotencyStore idempotency;
    @MockBean RateLimiter rateLimiter; // lets RateLimitFilter be constructed in the slice

    // The controller records metrics, so it needs a real registry. A simple
    // in-memory one is enough and keeps the test free of actuator wiring.
    @TestConfiguration
    static class TestMetrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void returns404WhenItemMissing() throws Exception {
        when(idempotency.find(any())).thenReturn(Optional.empty());
        when(repository.findById(99L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/work-items/99/triage"))
                .andExpect(status().isNotFound());

        verify(triageService, never()).triage(any()); // no LLM work for a missing item
    }

    @Test
    void replaysStoredResultWithoutCallingTriage() throws Exception {
        TriageResult stored = new TriageResult(1L, "RISK: low", "llm");
        when(idempotency.find("key-123")).thenReturn(Optional.of(stored));

        mvc.perform(post("/api/v1/work-items/1/triage").header("Idempotency-Key", "key-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("llm"))
                .andExpect(jsonPath("$.triage").value("RISK: low"));

        verify(triageService, never()).triage(any());   // replay: never re-calls the LLM
        verify(repository, never()).findById(anyLong()); // not even a DB read
    }

    @Test
    void computesAndStoresWhenKeyIsNew() throws Exception {
        WorkItem item = new WorkItem(1L, "T", "D", "open", "high", "unassigned", Instant.now());
        TriageResult fresh = new TriageResult(1L, "RISK: high", "llm");
        when(idempotency.find("new-key")).thenReturn(Optional.empty());
        when(repository.findById(1L)).thenReturn(Optional.of(item));
        when(triageService.triage(item)).thenReturn(fresh);

        mvc.perform(post("/api/v1/work-items/1/triage").header("Idempotency-Key", "new-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("llm"));

        verify(triageService).triage(item);            // fresh key: did the work
        verify(idempotency).save("new-key", fresh);    // and stored it for future replays
    }
}
