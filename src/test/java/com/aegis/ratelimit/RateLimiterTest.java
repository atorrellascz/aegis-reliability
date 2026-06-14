package com.aegis.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the rate limiter's Java side: how it interprets the Lua
 * script's reply and how it behaves when Redis misbehaves. The Lua logic itself
 * is exercised against a real Redis in an integration test; here we pin the
 * contract between Java and the script, with no Redis required.
 */
class RateLimiterTest {

    @SuppressWarnings("unchecked")
    private final RedisScript<List> script = mock(RedisScript.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RateLimiter limiter = new RateLimiter(redis, script);

    @Test
    void allowsWhenTokensRemain() {
        // Script says: allowed=1, remaining=19
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(1L, 19L));

        RateLimiter.Decision d = limiter.tryAcquire("rl:t1:POST:/triage", 20, 5);

        assertThat(d.allowed()).isTrue();
        assertThat(d.remaining()).isEqualTo(19L);
    }

    @Test
    void deniesWhenBucketEmpty() {
        // Script says: allowed=0, remaining=0
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(0L, 0L));

        RateLimiter.Decision d = limiter.tryAcquire("rl:t1:POST:/triage", 20, 5);

        assertThat(d.allowed()).isFalse();
        assertThat(d.remaining()).isEqualTo(0L);
    }

    @Test
    void failsOpenWhenRedisReturnsNull() {
        // Redis hiccup: the limiter must allow traffic rather than become the
        // outage. remaining=-1 signals "unknown / limiter bypassed".
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(null);

        RateLimiter.Decision d = limiter.tryAcquire("rl:t1:POST:/triage", 20, 5);

        assertThat(d.allowed()).isTrue();
        assertThat(d.remaining()).isEqualTo(-1L);
    }
}
