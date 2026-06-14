package com.aegis.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Cache-aside with single-flight stampede protection.

 * Plain cache-aside: on miss, load from source of truth (Postgres), write to
 * cache, return. The failure mode is the "cache stampede" / "thundering herd":
 * when a hot key expires, hundreds of concurrent requests all miss at once and
 * all hit the database together, which can take the database down precisely
 * when traffic is highest.

 * Mitigation here: a short-lived Redis lock (SET NX) so only ONE caller
 * recomputes the value on a miss; the others briefly back off and re-read. We
 * also add jitter to the TTL so a batch of keys written together does not all
 * expire on the same tick.
 */
@Component
public class CacheAsideService {

    private static final Logger log = LoggerFactory.getLogger(CacheAsideService.class);

    private final StringRedisTemplate redis;

    public CacheAsideService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String getOrLoad(String key, Duration ttl, Supplier<String> loader) {
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return cached; // hit
        }

        String lockKey = "lock:" + key;
        boolean gotLock = Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(5)));

        if (!gotLock) {
            // Someone else is already loading. Back off briefly, then re-read.
            sleep(50);
            String afterWait = redis.opsForValue().get(key);
            if (afterWait != null) {
                return afterWait;
            }
            // Still not there (loader slow / failed): fall through and load
            // ourselves rather than block the request forever.
        }

        try {
            String value = loader.get();           // expensive: hits Postgres
            long jitterMs = (long) (Math.random() * 2000); // +0..2s jitter
            redis.opsForValue().set(key, value, ttl.plusMillis(jitterMs));
            return value;
        } finally {
            redis.delete(lockKey);
        }
    }

    public void invalidate(String key) {
        redis.delete(key);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
