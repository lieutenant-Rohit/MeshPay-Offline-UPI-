package com.offline.payment.config;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CacheService {

    private static final String USER_CACHE_PREFIX = "user:";
    private static final String PACKET_HASH_PREFIX = "packet:";
    private static final String RATE_LIMIT_PREFIX = "ratelimit:";
    private static final String ACCOUNT_CACHE_PREFIX = "acct:";

    private static final long USER_CACHE_TTL_SECONDS = 900;
    private static final long PACKET_HASH_TTL_SECONDS = 172800;
    private static final long ACCOUNT_CACHE_TTL_SECONDS = 300;

    private final RedisTemplate<String, Object> redisTemplate;
    private final BloomFilter replayBloomFilter;

    public CacheService(RedisTemplate<String, Object> redisTemplate, BloomFilter replayBloomFilter) {
        this.redisTemplate = redisTemplate;
        this.replayBloomFilter = replayBloomFilter;
    }

    public void cacheUser(String vpa, String publicKeyBase64) {
        String key = USER_CACHE_PREFIX + vpa;
        redisTemplate.opsForValue().set(key, publicKeyBase64, USER_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public String getCachedUser(String vpa) {
        String key = USER_CACHE_PREFIX + vpa;
        Object val = redisTemplate.opsForValue().get(key);
        return val != null ? val.toString() : null;
    }

    public void evictUser(String vpa) {
        redisTemplate.delete(USER_CACHE_PREFIX + vpa);
    }

    public boolean isPacketHashProcessed(String packetHash) {
        if (!replayBloomFilter.mightContain(packetHash.getBytes())) {
            return false;
        }
        String key = PACKET_HASH_PREFIX + packetHash;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void markPacketHash(String packetHash) {
        replayBloomFilter.add(packetHash.getBytes());
        String key = PACKET_HASH_PREFIX + packetHash;
        redisTemplate.opsForValue().set(key, "1", PACKET_HASH_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public void cacheAccountBalance(String vpa, String balanceJson) {
        String key = ACCOUNT_CACHE_PREFIX + vpa;
        redisTemplate.opsForValue().set(key, balanceJson, ACCOUNT_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public String getCachedAccountBalance(String vpa) {
        String key = ACCOUNT_CACHE_PREFIX + vpa;
        Object val = redisTemplate.opsForValue().get(key);
        return val != null ? val.toString() : null;
    }

    public void evictAccount(String vpa) {
        redisTemplate.delete(ACCOUNT_CACHE_PREFIX + vpa);
    }

    public boolean tryAcquireRateLimit(String key, int maxRequests, long windowSeconds) {
        String redisKey = RATE_LIMIT_PREFIX + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count == null) return true;
        if (count == 1) {
            redisTemplate.expire(redisKey, windowSeconds, TimeUnit.SECONDS);
        }
        return count <= maxRequests;
    }
}
