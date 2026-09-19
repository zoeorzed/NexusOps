-- Optimistic compare-and-trim. Lists are newest first. Never rewrite retained items.
-- ARGV: ttl, keep count, summary, snapshot length, then each raw snapshot item.
local n = tonumber(ARGV[4])
if redis.call('LLEN', KEYS[1]) ~= n then return 0 end
for i = 1, n do
    if redis.call('LINDEX', KEYS[1], i - 1) ~= ARGV[4 + i] then return 0 end
end
local kind = redis.call('TYPE', KEYS[2]).ok
if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('Invalid summary key type') end
local previous = redis.call('GET', KEYS[2])
local summary = ARGV[3]
if previous and previous ~= '' then summary = previous .. '\n' .. summary end
redis.call('SET', KEYS[2], summary, 'EX', ARGV[1])
redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
redis.call('EXPIRE', KEYS[1], ARGV[1])
return 1
