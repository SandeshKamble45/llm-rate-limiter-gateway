-- Exact sliding-window rate limiter using a Redis sorted set.
--
-- Each request is stored with:
--   score  = request timestamp
--   member = unique request ID
--
-- Redis Lua executes the complete operation atomically:
--
--   remove expired requests
--   count current requests
--   decide whether request is allowed
--   add request
--   calculate retry time
--
-- KEYS[1] = window key
--
-- ARGV[1] = window size in seconds
-- ARGV[2] = maximum requests
-- ARGV[3] = unique request ID
--
-- Return:
--   { allowed, current_count_after_request, retry_after_seconds }

local key = KEYS[1]

local window_seconds = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local request_id = ARGV[3]

-- Redis is the authoritative clock.
local redis_time = redis.call("TIME")
local now_seconds = tonumber(redis_time[1])
local now_microseconds = tonumber(redis_time[2])

local now = (now_seconds * 1000)
           + math.floor(now_microseconds / 1000)

local window_milliseconds = window_seconds * 1000
local window_start = now - window_milliseconds

-- Remove requests that are outside the sliding window.
redis.call(
    "ZREMRANGEBYSCORE",
    key,
    0,
    window_start
)

local current_count = redis.call("ZCARD", key)

local allowed = 0
local retry_after_seconds = 0

if current_count < max_requests then

    -- There is capacity for this request.
    redis.call(
        "ZADD",
        key,
        now,
        request_id
    )

    current_count = current_count + 1
    allowed = 1

else

    -- Window is full.
    --
    -- The oldest request is the first request that can leave
    -- the window and therefore determines when another request
    -- can be admitted.
    local oldest = redis.call(
        "ZRANGE",
        key,
        0,
        0,
        "WITHSCORES"
    )

    if oldest[2] ~= nil then
        local oldest_timestamp = tonumber(oldest[2])

        local retry_after_milliseconds =
            (oldest_timestamp + window_milliseconds) - now

        retry_after_seconds = math.max(
            1,
            math.ceil(retry_after_milliseconds / 1000)
        )
    else
        retry_after_seconds = 1
    end

end

-- Keep inactive tenant keys from remaining forever.
redis.call(
    "EXPIRE",
    key,
    window_seconds + 1
)

return {
    allowed,
    current_count,
    retry_after_seconds
}