-- KEYS[1] = daily spend key; KEYS[2] = reservation hash (same Redis Cluster hash tag)
-- ARGV[1] = actual microdollars; ARGV[2] = budget; ARGV[3] = TTL seconds
local actual = tonumber(ARGV[1])
local budget = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])
local status = redis.call("HGET", KEYS[2], "status")
local current = tonumber(redis.call("GET", KEYS[1]) or "0")

if status == "settled" then
    return {
        1,
        tonumber(redis.call("HGET", KEYS[2], "reserved")),
        tonumber(redis.call("HGET", KEYS[2], "actual")),
        tonumber(redis.call("HGET", KEYS[2], "adjustment")),
        tonumber(redis.call("HGET", KEYS[2], "spent")),
        tonumber(redis.call("HGET", KEYS[2], "remaining")),
        tonumber(redis.call("HGET", KEYS[2], "within_budget"))
    }
end

if status ~= "reserved" then
    return { 0, 0, actual, 0, current, math.max(0, budget - current), 0 }
end
if actual < 0 then
    return redis.error_reply("actual cost cannot be negative")
end

local reserved = tonumber(redis.call("HGET", KEYS[2], "reserved"))
local adjustment = actual - reserved
local new_spent = math.max(0, current + adjustment)
local within_budget = new_spent <= budget and 1 or 0
local remaining = math.max(0, budget - new_spent)

redis.call("SET", KEYS[1], new_spent)
redis.call("HSET", KEYS[2],
    "status", "settled",
    "actual", actual,
    "adjustment", adjustment,
    "spent", new_spent,
    "remaining", remaining,
    "within_budget", within_budget)
redis.call("EXPIRE", KEYS[1], ttl)
redis.call("EXPIRE", KEYS[2], ttl)

return { 1, reserved, actual, adjustment, new_spent, remaining, within_budget }
