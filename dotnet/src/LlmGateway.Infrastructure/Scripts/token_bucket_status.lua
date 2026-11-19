local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local bucket = redis.call("HMGET", key, "tokens", "last_refill")
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])
if tokens == nil or last_refill == nil then
    return { capacity }
end
local redis_time = redis.call("TIME")
local now = tonumber(redis_time[1]) + (tonumber(redis_time[2]) / 1000000)
local elapsed = math.max(0, now - last_refill)
local available = math.min(capacity, tokens + elapsed * refill_rate)
return { available }
