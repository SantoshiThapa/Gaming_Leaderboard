package com.leaderboard.api;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
public class ScoreService {

    public static final List<String> MODES = List.of("blitz", "bullet", "rapid");
    public static final String CHANNEL = "lb:updates";
    static final int START_RATING = 1500;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public ScoreService(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public static String normalizeMode(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(m)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be one of " + MODES);
        }
        return m;
    }

    /** Writes to Postgres (source of truth), then updates Redis and notifies subscribers after commit. */
    @Transactional
    public ScoreResult submit(ScoreRequest req) {
        final String mode = normalizeMode(req.mode());
        final String username = req.username() == null ? "" : req.username().trim();
        if (username.isEmpty() || username.length() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username is required (max 40 chars)");
        }
        if (req.rating() == null && req.delta() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "send either rating or delta");
        }
        String source = req.source() == null || req.source().isBlank() ? "api" : req.source().trim();
        if (source.length() > 16) source = source.substring(0, 16);

        Long playerId = jdbc.queryForObject(
                "INSERT INTO players(username) VALUES (?) "
                        + "ON CONFLICT (username) DO UPDATE SET username = EXCLUDED.username RETURNING id",
                Long.class, username);

        List<Integer> current = jdbc.queryForList(
                "SELECT rating FROM player_mode_stats WHERE player_id = ? AND mode = ? FOR UPDATE",
                Integer.class, playerId, mode);
        int before = current.isEmpty() ? START_RATING : current.get(0);
        int after = req.rating() != null ? req.rating() : before + req.delta();
        final int rating = Math.max(0, Math.min(4000, after));

        jdbc.update("""
                INSERT INTO player_mode_stats(player_id, mode, rating, games, peak)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT (player_id, mode) DO UPDATE SET
                    rating = EXCLUDED.rating,
                    games = player_mode_stats.games + 1,
                    peak = GREATEST(player_mode_stats.peak, EXCLUDED.rating),
                    updated_at = now()
                """, playerId, mode, rating, rating);
        jdbc.update("INSERT INTO score_history(player_id, mode, rating, delta, source) VALUES (?, ?, ?, ?, ?)",
                playerId, mode, rating, rating - before, source);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.opsForZSet().add(RankingService.key(mode), username, rating);
                redis.convertAndSend(CHANNEL, mode);
            }
        });
        return new ScoreResult(username, mode, rating, rating - before);
    }
}
