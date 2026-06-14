-- token_bucket.lua
-- Atomic distributed token-bucket rate limiter.
--
-- Why Lua: the read-modify-write of the bucket must be atomic. Doing GET then
-- SET from the application would race under concurrency (two callers both read
-- "1 token left" and both proceed). Redis runs a Lua script as a single atomic
-- step, so the whole refill+consume decision is race-free without distributed locks.
--
-- KEYS[1] = bucket key, e.g. "rl:{tenant-42}:POST:/triage"
-- ARGV[1] = capacity        (max tokens / burst size)
-- ARGV[2] = refill_per_sec  (sustained rate)
-- ARGV[3] = now_ms          (caller clock in milliseconds)
-- ARGV[4] = requested       (tokens this request costs, usually 1)
--
-- Returns: { allowed (1|0), tokens_remaining }

local capacity   = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now_ms     = tonumber(ARGV[3])
local requested  = tonumber(ARGV[4])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local last_ts = tonumber(state[2])

-- First time we see this bucket: start full.
if tokens == nil then
    tokens = capacity
    last_ts = now_ms
end

-- Refill based on elapsed wall-clock time since the last update.
local elapsed_sec = math.max(0, now_ms - last_ts) / 1000.0
tokens = math.min(capacity, tokens + (elapsed_sec * refill_rate))

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)

-- Expire idle buckets so we don't leak keys for one-off tenants.
-- TTL = time to refill a full bucket from empty, plus a small margin.
local ttl = math.ceil(capacity / refill_rate) + 1
redis.call('EXPIRE', KEYS[1], ttl)

return { allowed, math.floor(tokens) }
