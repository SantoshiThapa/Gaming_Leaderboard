package com.leaderboard.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** Read side: Redis sorted sets (one per mode). Rebuilt from Postgres when empty. */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public RankingService(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    public static String key(String mode) {
        return "lb:" + mode;
    }

    public List<Entry> top(String mode, int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return range(mode, 0, n - 1);
    }

    private List<Entry> range(String mode, long start, long end) {
        Set<TypedTuple<String>> set = redis.opsForZSet().reverseRangeWithScores(key(mode), start, end);
        List<Entry> out = new ArrayList<>();
        if (set == null) return out;
        int rank = (int) start + 1;
        for (TypedTuple<String> t : set) {
            out.add(new Entry(rank++, t.getValue(), t.getScore() == null ? 0 : (int) Math.round(t.getScore())));
        }
        return out;
    }

    public long size(String mode) {
        Long n = redis.opsForZSet().zCard(key(mode));
        return n == null ? 0 : n;
    }

    /** The player plus {@code radius} neighbours on each side. */
    public Optional<Map<String, Object>> around(String mode, String name, int radius) {
        Optional<String> canonical = canonical(name);
        if (canonical.isEmpty()) return Optional.empty();
        Long r = redis.opsForZSet().reverseRank(key(mode), canonical.get());
        if (r == null) return Optional.empty();
        int rad = Math.max(1, Math.min(radius, 10));
        List<Entry> entries = range(mode, Math.max(0, r - rad), r + rad);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", canonical.get());
        out.put("mode", mode);
        out.put("rank", r + 1);
        out.put("total", size(mode));
        out.put("entries", entries);
        return Optional.of(out);
    }

    private Optional<String> canonical(String name) {
        return jdbc.queryForList("SELECT username FROM players WHERE lower(username) = lower(?) LIMIT 1",
                String.class, name).stream().findFirst();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        for (String mode : ScoreService.MODES) {
            try {
                if (Boolean.TRUE.equals(redis.hasKey(key(mode)))) continue;
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT p.username, s.rating FROM player_mode_stats s "
                                + "JOIN players p ON p.id = s.player_id WHERE s.mode = ?", mode);
                Set<TypedTuple<String>> batch = new HashSet<>();
                for (Map<String, Object> row : rows) {
                    batch.add(new DefaultTypedTuple<>((String) row.get("username"),
                            ((Number) row.get("rating")).doubleValue()));
                    if (batch.size() >= 1000) {
                        redis.opsForZSet().add(key(mode), batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) redis.opsForZSet().add(key(mode), batch);
                log.info("Redis warm-up for {}: {} players", mode, rows.size());
            } catch (Exception e) {
                log.warn("Warm-up failed for {}: {}", mode, e.getMessage());
            }
        }
    }
}
