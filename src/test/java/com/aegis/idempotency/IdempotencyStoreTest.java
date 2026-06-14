package com.aegis.idempotency;

import com.aegis.ai.TriageService.TriageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Redis-backed idempotency store. Redis is mocked, but the
 * JSON round-trip uses a real ObjectMapper so we actually prove a TriageResult
 * survives serialize -> store -> load unchanged.
 */
class IdempotencyStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final IdempotencyStore store = new IdempotencyStore(redis, mapper, Duration.ofHours(24));

    @Test
    void savesResultAsJsonWithTtl() throws Exception {
        when(redis.opsForValue()).thenReturn(ops);
        TriageResult result = new TriageResult(1L, "RISK: low\nNEXT: ...", "llm");

        store.save("key-123", result);

        String expectedJson = mapper.writeValueAsString(result);
        verify(ops).set(eq("idem:key-123"), eq(expectedJson), eq(Duration.ofHours(24)));
    }

    @Test
    void findReturnsStoredResult() throws Exception {
        TriageResult result = new TriageResult(1L, "RISK: low", "llm");
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("idem:key-123")).thenReturn(mapper.writeValueAsString(result));

        Optional<TriageResult> found = store.find("key-123");

        assertThat(found).contains(result);
    }

    @Test
    void findReturnsEmptyOnMiss() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(any())).thenReturn(null);

        assertThat(store.find("never-seen")).isEmpty();
    }

    @Test
    void corruptEntryIsTreatedAsMiss() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(any())).thenReturn("{not valid json");

        // A bad cache value must never break the request path.
        assertThat(store.find("key-123")).isEmpty();
    }
}
