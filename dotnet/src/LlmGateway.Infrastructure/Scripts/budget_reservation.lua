-- KEYS[1] = daily spend key; KEYS[2] = reservation hash (same Redis Cluster hash tag)
-- ARGV[1] = requested microdollars; ARGV[2] = budget; ARGV[3] = TTL seconds
local requested = tonumber(ARGV[1])
local budget = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])
local existing_status = redis.call("HGET", KEYS[2], "status")
local current = tonumber(redis.call("GET", KEYS[1]) or "0")

-- A repeated reservation ID never increments spend a second time.
if existing_status == "reserved" then
    return { 2, current, tonumber(redis.call("HGET", KEYS[2], "reserved")) }
elseif existing_status == "settled" then
    return { 3, current, tonumber(redis.call("HGET", KEYS[2], "reserved")) }
end

if requested <= 0 then
    return redis.error_reply("requested reservation must be positive")
end
if current + requested > budget then
    return { 0, current, 0 }
end

local new_spent = redis.call("INCRBY", KEYS[1], requested)
redis.call("HSET", KEYS[2], "status", "reserved", "reserved", requested)
redis.call("EXPIRE", KEYS[1], ttl)
redis.call("EXPIRE", KEYS[2], ttl)
return { 1, new_spent, requested }
