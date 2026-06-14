package com.aegis.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for cache-aside behaviour with Redis mocked: a hit must skip the
 * loader entirely, and a miss must load from source, write through to the cache,
 * and release the single-flight lock.
 */
class CacheAsideServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final CacheAsideService cache = new CacheAsideService(redis);

    @Test
    void returnsCachedValueWithoutCallingLoader() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("wi:1")).thenReturn("cached-title");

        AtomicInteger loaderCalls = new AtomicInteger(0);
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "loaded-title";
        };

        String value = cache.getOrLoad("wi:1", Duration.ofSeconds(30), loader);

        assertThat(value).isEqualTo("cached-title");
        assertThat(loaderCalls.get()).isZero(); // hit: the expensive loader never runs
    }

    @Test
    void onMissLoadsFromSourceAndCaches() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("wi:1")).thenReturn(null); // miss
        when(ops.setIfAbsent(eq("lock:wi:1"), eq("1"), any(Duration.class)))
                .thenReturn(true);              // we win the single-flight lock

        String value = cache.getOrLoad("wi:1", Duration.ofSeconds(30), () -> "loaded-title");

        assertThat(value).isEqualTo("loaded-title");
        verify(ops).set(eq("wi:1"), eq("loaded-title"), any(Duration.class)); // wrote through to cache
        verify(redis).delete("lock:wi:1");                                    // released the lock
    }
}
