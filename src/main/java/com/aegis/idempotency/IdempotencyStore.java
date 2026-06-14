package com.aegis.idempotency;

import com.aegis.ai.TriageService.TriageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Durable idempotency store backed by Redis.
 *
 * AI agents retry aggressively, so the same logical triage request can arrive
 * many times. Storing the first result under the client's Idempotency-Key and
 * replaying it makes those retries safe and cheap: we never re-call the LLM for
 * a key we have already answered.
 *
 * v1 kept this map in process memory, which has two problems: it is lost on
 * restart, and it is not shared across instances (so a retry routed to another
 * pod would re-call the LLM). Redis fixes both, and a TTL bounds how long we
 * remember a key so the store cannot grow without limit.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);
    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public IdempotencyStore(StringRedisTemplate redis,
                            ObjectMapper mapper,
                            @Value("${aegis.idempotency.ttl:24h}") Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    /**
     * Returns the stored result for this key, or empty if we have never seen it
     * (or the entry has expired). A corrupt entry is treated as a miss so a bad
     * cache value can never break the request path.
     */
    public Optional<TriageResult> find(String idempotencyKey) {
        String json = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, TriageResult.class));
        } catch (Exception e) {
            log.warn("Discarding unreadable idempotency entry for key {}: {}",
                    idempotencyKey, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Persists the result under the key with a bounded TTL. Idempotency here is
     * a best-effort optimization, not a correctness invariant: if the write
     * fails the worst case is one extra LLM call on a later retry, so we log and
     * continue rather than fail the user's request.
     */
    public void save(String idempotencyKey, TriageResult result) {
        try {
            String json = mapper.writeValueAsString(result);
            redis.opsForValue().set(KEY_PREFIX + idempotencyKey, json, ttl);
        } catch (Exception e) {
            log.warn("Could not store idempotency entry for key {}: {}",
                    idempotencyKey, e.toString());
        }
    }
}