-- Atomically settle a previously reserved budget amount.
--
-- KEYS[1] = daily quota key
--
-- ARGV[1] = reserved amount
-- ARGV[2] = actual amount
-- ARGV[3] = daily budget
-- ARGV[4] = TTL in seconds
--
-- Return:
--   {
--      reserved,
--      actual,
--      adjustment,
--      new_spent,
--      remaining,
--      within_budget
--   }

local key = KEYS[1]

local reserved = tonumber(ARGV[1])
local actual = tonumber(ARGV[2])
local budget = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local current_spent =
    tonumber(redis.call("GET", key) or "0")

local adjustment =
    actual - reserved

local new_spent =
    current_spent + adjustment

local within_budget = 1

if new_spent > budget then
    within_budget = 0
end

-- Do not allow accounting to become negative.
new_spent = math.max(0, new_spent)

redis.call(
    "SET",
    key,
    new_spent
)

redis.call(
    "EXPIRE",
    key,
    ttl
)

local remaining =
    math.max(0, budget - new_spent)

return {
    reserved,
    actual,
    adjustment,
    new_spent,
    remaining,
    within_budget
}