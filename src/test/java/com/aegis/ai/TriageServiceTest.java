package com.aegis.ai;

import com.aegis.ai.TriageService.TriageResult;
import com.aegis.workitem.WorkItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for triage. The happy path checks we tag a real LLM answer with
 * source "llm"; the fallback tests pin the deterministic heuristic that keeps
 * the endpoint useful when the LLM is unavailable (graceful degradation).
 */
class TriageServiceTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final TriageService service = new TriageService(llm);

    private static WorkItem item(String status, String priority) {
        return new WorkItem(1L, "Title", "Description", status, priority, "unassigned", Instant.now());
    }

    @Test
    void happyPathTagsResultAsLlm() {
        when(llm.summarizeTriage(anyString())).thenReturn("RISK: high\nNEXT: page on-call");

        TriageResult result = service.triage(item("open", "high"));

        assertThat(result.source()).isEqualTo("llm");
        assertThat(result.workItemId()).isEqualTo(1L);
        assertThat(result.triage()).isEqualTo("RISK: high\nNEXT: page on-call");
    }

    @Test
    void fallbackMapsHighPriorityOpenItem() {
        TriageResult result = service.fallbackTriage(item("open", "high"), new RuntimeException("llm down"));

        assertThat(result.source()).isEqualTo("fallback");
        assertThat(result.triage()).contains("RISK: high");
        assertThat(result.triage()).contains("Assign an owner"); // open -> needs an owner
    }

    @Test
    void fallbackMapsLowPriorityNonOpenItem() {
        TriageResult result = service.fallbackTriage(item("done", "low"), new RuntimeException());

        assertThat(result.source()).isEqualTo("fallback");
        assertThat(result.triage()).contains("RISK: low");
        assertThat(result.triage()).contains("Review current status"); // not open -> just review
    }
}
