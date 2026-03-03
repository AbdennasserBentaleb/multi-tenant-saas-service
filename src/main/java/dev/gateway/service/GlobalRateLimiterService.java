package dev.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class GlobalRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(GlobalRateLimiterService.class);

    private final StringRedisTemplate redisTemplate;

    public GlobalRateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // A simple fixed window rate limiter Lua script.
    // This is a FIXED-WINDOW counter (not sliding window). The trade-off is
    // that a client could theoretically send 2x the limit across a window
    // boundary. For a noisy-neighbor guard in a B2B SaaS context, this
    // is an acceptable approximation. A sliding window would require a
    // sorted set approach (ZADD/ZREMRANGEBYSCORE) which is more expensive.
    private static final String LUA_SCRIPT =
            "local key = KEYS[1]\n" +
            "local limit = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2])\n" +
            "local current = redis.call('get', key)\n" +
            "if current and tonumber(current) >= limit then\n" +
            "  return 0\n" +
            "end\n" +
            "current = redis.call('incr', key)\n" +
            "if tonumber(current) == 1 then\n" +
            "  redis.call('expire', key, window)\n" +
            "end\n" +
            "return 1\n";

    // Script compiled once at class-load time — NOT per-request.
    // Instantiating DefaultRedisScript on every call would cause unnecessary
    // object allocation and GC pressure under high throughput.
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setScriptText(LUA_SCRIPT);
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    public boolean isAllowed(String tenantId) {
        String key = "ratelimit:tenant:" + tenantId;
        // 100 requests per 1-second fixed window per tenant
        Long result = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                Collections.singletonList(key),
                "100",
                "1"
        );
        return result != null && result == 1L;
    }
}
