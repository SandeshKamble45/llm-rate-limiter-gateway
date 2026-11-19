-- Match the admission script's open lower boundary: events at or before
-- the exact window start are expired and must not be shown as active.
local key = KEYS[1]
local window_seconds = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local redis_time = redis.call("TIME")
local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
local window_start = now - (window_seconds * 1000)
local count = redis.call("ZCOUNT", key, "(" .. window_start, "+inf")
return { count, max_requests, window_seconds, math.max(0, max_requests - count) }
