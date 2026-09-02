-- Atomic token bucket, executed inside Redis so concurrent gateway instances
-- never race on read-then-write. This is the piece you'll be asked to explain
-- in a systems design interview: WHY does this need to be a Lua script and not
-- three separate Redis calls from Java?
-- Answer: GET capacity -> compute -> SET is a classic check-then-act race.
-- Two instances can both read "5 tokens left", both allow the request, and you've
-- now let through double what the bucket permits. Lua scripts run atomically on
-- the Redis server (single-threaded), so this whole block is one indivisible step.

-- KEYS[1] = bucket key, e.g. "ratelimit:tenant123:gpt-4"
-- ARGV[1] = capacity (max tokens the bucket can hold)
-- ARGV[2] = refill_rate (tokens added per second)
-- ARGV[3] = requested_tokens (cost of this request, e.g. prompt+completion tokens)
-- ARGV[4] = now (unix timestamp, seconds, as float)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

local bucket = redis.call("HMGET", key, "tokens", "last_refill")
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    last_refill = now
end

-- Refill based on elapsed time since last request
local elapsed = math.max(0, now - last_refill)
tokens = math.min(capacity, tokens + (elapsed * refill_rate))

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call("HMSET", key, "tokens", tokens, "last_refill", now)
-- Let the bucket expire if unused for a while, so idle tenants don't leak memory
redis.call("EXPIRE", key, 3600)

return { allowed, tokens }
