local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local redis_time = redis.call("TIME")
local now = tonumber(redis_time[1]) + (tonumber(redis_time[2]) / 1000000)

local bucket = redis.call("HMGET", key, "tokens", "last_refill")
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    last_refill = now
end

local elapsed = math.max(0, now - last_refill)
tokens = math.min(capacity, tokens + (elapsed * refill_rate))

local allowed = 0
local retry_after_seconds = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
else
    local missing = requested - tokens
    retry_after_seconds = refill_rate > 0 and math.ceil(missing / refill_rate) or -1
end

redis.call("HSET", key, "tokens", tokens, "last_refill", now)
redis.call("EXPIRE", key, 3600)
return { allowed, tokens, retry_after_seconds }
