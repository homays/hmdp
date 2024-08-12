local window_start = tonumber(ARGV[1]) - 60000
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, window_start)
local current_requests = redis.call('ZCARD', KEYS[1])

if current_requests < tonumber(ARGV[3]) then
    redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
    return 1
else
    return 0
end