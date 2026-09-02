-- Sliding window log using a Redis sorted set. Keep this as your second
-- algorithm implementation - in interviews you should be able to say WHY
-- you'd pick this over token bucket: it gives exact precision (no burst
-- averaging artifacts) at the cost of O(log n) memory per key instead of O(1).

-- KEYS[1] = window key, e.g. "ratelimit:sw:tenant123"
-- ARGV[1] = window_size_seconds
-- ARGV[2] = max_requests
-- ARGV[3] = now (unix timestamp, milliseconds)

local key = KEYS[1]
local window = tonumber(ARGV[1]) * 1000
local max_requests = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local window_start = now - window

-- Drop entries older than the window
redis.call("ZREMRANGEBYSCORE", key, 0, window_start)

local current_count = redis.call("ZCARD", key)

local allowed = 0
if current_count < max_requests then
    redis.call("ZADD", key, now, now .. "-" .. math.random())
    allowed = 1
end

redis.call("EXPIRE", key, math.ceil(window / 1000) + 1)

return { allowed, current_count }
