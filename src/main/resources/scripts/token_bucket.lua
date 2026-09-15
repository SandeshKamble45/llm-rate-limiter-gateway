-- Atomic token bucket, executed inside Redis so concurrent gateway instances
-- never race on read-then-write.
--
-- The entire operation is atomic because Redis executes this Lua script
-- as one indivisible operation.
--
-- KEYS[1] = bucket key
--
-- ARGV[1] = capacity
-- ARGV[2] = refill rate (tokens per second)
-- ARGV[3] = requested tokens
--
-- Return:
--   { allowed, remaining_tokens, retry_after_seconds }

local key = KEYS[1]

local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

-- Redis is the authoritative clock.
local redis_time = redis.call("TIME")
local now_seconds = tonumber(redis_time[1])
local now_microseconds = tonumber(redis_time[2])

local now = now_seconds + (now_microseconds / 1000000)

local bucket = redis.call(
    "HMGET",
    key,
    "tokens",
    "last_refill"
)

local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

-- First request for this bucket.
if tokens == nil then
    tokens = capacity
    last_refill = now
end

-- Refill tokens based on elapsed time.
local elapsed = math.max(
    0,
    now - last_refill
)

tokens = math.min(
    capacity,
    tokens + (elapsed * refill_rate)
)

local allowed = 0
local retry_after_seconds = 0

if tokens >= requested then

    -- Enough tokens are available.
    tokens = tokens - requested
    allowed = 1

else

    -- Not enough tokens are available.
    --
    -- Calculate how long it takes to accumulate
    -- the missing tokens.
    local missing_tokens = requested - tokens

    if refill_rate > 0 then
        retry_after_seconds = math.ceil(
            missing_tokens / refill_rate
        )
    else
        retry_after_seconds = -1
    end

end

-- Persist bucket state.
redis.call(
    "HSET",
    key,
    "tokens",
    tokens,
    "last_refill",
    now
)

-- Remove idle buckets after one hour.
redis.call(
    "EXPIRE",
    key,
    3600
)

return {
    allowed,
    tokens,
    retry_after_seconds
}