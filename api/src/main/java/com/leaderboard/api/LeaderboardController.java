package com.leaderboard.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api")
public class LeaderboardController {

    private final ScoreService scores;
    private final RankingService ranking;
    private final JdbcTemplate jdbc;
    private final String instanceId;

    public LeaderboardController(ScoreService scores, RankingService ranking, JdbcTemplate jdbc,
                                 @Value("${app.instance-id}") String instanceId) {
        this.scores = scores;
        this.ranking = ranking;
        this.jdbc = jdbc;
        this.instanceId = instanceId;
    }

    @PostMapping("/scores")
    public ScoreResult submit(@RequestBody ScoreRequest request) {
        return scores.submit(request);
    }

    @PostMapping("/scores/batch")
    public Map<String, Object> batch(@RequestBody List<ScoreRequest> requests) {
        if (requests.size() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "max 500 items per batch");
        }
        int accepted = 0;
        for (ScoreRequest r : requests) {
            try {
                scores.submit(r);
                accepted++;
            } catch (ResponseStatusException ignored) {
                // counted as rejected
            }
        }
        return Map.of("accepted", accepted, "rejected", requests.size() - accepted);
    }

    @GetMapping("/leaderboard/{mode}")
    public Map<String, Object> top(@PathVariable String mode, @RequestParam(defaultValue = "50") int limit) {
        String m = ScoreService.normalizeMode(mode);
        return Map.of("type", "snapshot", "mode", m, "total", ranking.size(m), "entries", ranking.top(m, limit));
    }

    @GetMapping("/leaderboard/{mode}/player/{username}")
    public ResponseEntity<Map<String, Object>> around(@PathVariable String mode, @PathVariable String username,
                                                      @RequestParam(defaultValue = "3") int radius) {
        String m = ScoreService.normalizeMode(mode);
        return ranking.around(m, username, radius)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/players/{username}")
    public ResponseEntity<Map<String, Object>> profile(@PathVariable String username) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.username, p.created_at, s.mode, s.rating, s.games, s.peak, s.updated_at
                FROM players p LEFT JOIN player_mode_stats s ON s.player_id = p.id
                WHERE lower(p.username) = lower(?) ORDER BY s.mode
                """, username);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", rows.get(0).get("username"));
        out.put("createdAt", rows.get(0).get("created_at"));
        List<Map<String, Object>> modes = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r.get("mode") == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", r.get("mode"));
            m.put("rating", r.get("rating"));
            m.put("games", r.get("games"));
            m.put("peak", r.get("peak"));
            modes.add(m);
        }
        out.put("modes", modes);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/players/{username}/history")
    public List<Map<String, Object>> history(@PathVariable String username,
                                             @RequestParam(required = false) String mode,
                                             @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        String base = "SELECT h.mode, h.rating, h.delta, h.source, h.created_at FROM score_history h "
                + "JOIN players p ON p.id = h.player_id WHERE lower(p.username) = lower(?) ";
        if (mode != null && !mode.isBlank()) {
            return jdbc.queryForList(base + "AND h.mode = ? ORDER BY h.created_at DESC, h.id DESC LIMIT ?",
                    username, ScoreService.normalizeMode(mode), n);
        }
        return jdbc.queryForList(base + "ORDER BY h.created_at DESC, h.id DESC LIMIT ?", username, n);
    }

    @GetMapping("/instance")
    public Map<String, String> instance() {
        return Map.of("instance", instanceId);
    }
}
