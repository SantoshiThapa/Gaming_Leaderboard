package com.leaderboard.api;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** Tiny Redis lease so only one API instance runs the ingestor/simulator at a time. */
@Component
public class LeaderLock {

    private static final String KEY = "lb:ingest:leader";
    private static final Duration TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final String token = UUID.randomUUID().toString();

    public LeaderLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLeader() {
        try {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY, token, TTL))) return true;
            if (token.equals(redis.opsForValue().get(KEY))) {
                redis.expire(KEY, TTL);
                return true;
            }
        } catch (Exception ignored) {
            // Redis hiccup: skip this tick
        }
        return false;
    }
}
