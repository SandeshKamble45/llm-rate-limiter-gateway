-- Atomic daily budget reservation.
--
-- The gateway must reserve budget BEFORE calling the LLM.
-- GET -> check -> INCR cannot be split across separate Redis calls,
-- because concurrent gateway instances could both pass the check.
--
-- KEYS[1] = daily budget key
--
-- ARGV[1] = requested reservation in integer microdollars
-- ARGV[2] = daily budget in integer microdollars
-- ARGV[3] = TTL in seconds
--
-- Return:
--   { 1, new_spent } if reservation succeeds
--   { 0, current_spent } if reservation is rejected

local key = KEYS[1]

local requested = tonumber(ARGV[1])
local budget = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])

local current_spent = tonumber(redis.call("GET", key) or "0")

if current_spent + requested > budget then
    return { 0, current_spent }
end

local new_spent = redis.call("INCRBY", key, requested)

redis.call("EXPIRE", key, ttl)

return { 1, new_spent }