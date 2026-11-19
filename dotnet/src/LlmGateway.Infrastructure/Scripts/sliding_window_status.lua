local key = KEYS[1]
local window_seconds = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local redis_time = redis.call("TIME")
local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
local window_start = now - (window_seconds * 1000)
redis.call("ZREMRANGEBYSCORE", key, "-inf", window_start)
local count = redis.call("ZCARD", key)
redis.call("EXPIRE", key, window_seconds + 1)
return { count, max_requests, window_seconds, math.max(0, max_requests - count) }
