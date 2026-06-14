package com.aegis.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Distributed token-bucket rate limiter backed by Redis.

 * Token bucket (vs fixed-window / sliding-window) is chosen because it allows
 * short bursts up to {@code capacity} while still enforcing a sustained
 * {@code refillPerSec} rate -- exactly what you want in front of an AI endpoint
 * where an agent may legitimately burst, but must not hammer the backend
 * indefinitely.

 * The decision is computed inside a single Lua script so the refill+consume is
 * atomic across every application instance sharing the same Redis.
 */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<List> tokenBucketScript;

    public RateLimiter(StringRedisTemplate redis, RedisScript<List> tokenBucketScript) {
        this.redis = redis;
        this.tokenBucketScript = tokenBucketScript;
    }

    public record Decision(boolean allowed, long remaining) {}

    /**
     * @param key          identity to limit on, e.g. "rl:tenant-42:POST:/triage"
     * @param capacity     burst size (max tokens)
     * @param refillPerSec sustained tokens added per second
     */
    public Decision tryAcquire(String key, int capacity, double refillPerSec) {
        long now = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) redis.execute(
                tokenBucketScript,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillPerSec),
                String.valueOf(now),
                "1");

        // Fail-open on Redis hiccups: if the limiter itself is down we do not
        // want it to become the outage. (A stricter system might fail-closed;
        // that is a deliberate trade-off worth stating explicitly.)
        if (result == null || result.size() < 2) {
            return new Decision(true, -1);
        }
        return new Decision(result.get(0) == 1L, result.get(1));
    }
}
