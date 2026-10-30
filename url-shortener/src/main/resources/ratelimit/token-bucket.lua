-- Atomic token bucket for RedisRateLimiter. Redis runs a script without interleaving other commands, so the
-- read-refill-take-write sequence below is atomic across all application nodes.
--
-- KEYS[1]  bucket key (one hash per client): fields 'units' (tokens left) and 'ts' (last refill, epoch millis)
-- ARGV[1]  capacity in tokens (1..1000000)
-- ARGV[2]  refill rate in tokens per minute (1..1000000)
-- ARGV[3]  current time in epoch milliseconds, read from the application clock
-- ARGV[4]  tokens requested (1..capacity)
--
-- Returns {allowed (1 or 0), whole tokens left, milliseconds to wait (0 when allowed)}.
--
-- Amounts are integers in units of 1/60000 token: a refill of R tokens per minute is exactly R units per
-- millisecond, so every value stays an exact integer (all far below 2^53) and the result is identical to the
-- in-memory TokenBucketRateLimiter. A clock reading older than 'ts' (skew between nodes) neither refills nor
-- rewinds the bucket, so skew can delay a refill but never mint tokens.
local units_per_token = 60000
local capacity = tonumber(ARGV[1]) * units_per_token
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4]) * units_per_token

local state = redis.call('HMGET', KEYS[1], 'units', 'ts')
local units = tonumber(state[1])
local ts = tonumber(state[2])
if units == nil or ts == nil then
  units = capacity
  ts = now
elseif now > ts then
  units = math.min(capacity, units + (now - ts) * rate)
  ts = now
end

local allowed = 0
local wait = 0
if units >= requested then
  units = units - requested
  allowed = 1
else
  wait = math.floor((requested - units + rate - 1) / rate)
end

redis.call('HSET', KEYS[1], 'units', string.format('%d', units), 'ts', string.format('%d', ts))
-- An idle bucket is dropped once it would be full again, which is equivalent to a fresh bucket.
redis.call('PEXPIRE', KEYS[1], math.max(1, math.floor((capacity - units + rate - 1) / rate)))
return {allowed, math.floor(units / units_per_token), wait}
