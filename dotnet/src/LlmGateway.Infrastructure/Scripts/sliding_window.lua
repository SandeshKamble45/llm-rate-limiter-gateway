local key = KEYS[1]
local window_seconds = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local request_id = ARGV[3]

local redis_time = redis.call("TIME")
local now = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
local window_milliseconds = window_seconds * 1000
local window_start = now - window_milliseconds

redis.call("ZREMRANGEBYSCORE", key, "-inf", window_start)
local current_count = redis.call("ZCARD", key)
local allowed = 0
local retry_after_seconds = 0

if current_count < max_requests then
    redis.call("ZADD", key, now, request_id)
    current_count = current_count + 1
    allowed = 1
else
    local oldest = redis.call("ZRANGE", key, 0, 0, "WITHSCORES")
    if oldest[2] ~= nil then
        local wait_ms = (tonumber(oldest[2]) + window_milliseconds) - now
        retry_after_seconds = math.max(1, math.ceil(wait_ms / 1000))
    else
        retry_after_seconds = 1
    end
end

redis.call("EXPIRE", key, window_seconds + 1)
return { allowed, current_count, retry_after_seconds }
