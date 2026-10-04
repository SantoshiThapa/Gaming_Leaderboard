package com.leaderboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Pulls the real top-50 per mode from the public Lichess API. Falls back silently to the simulator on failure. */
@Component
public class LichessIngestor {

    private static final Logger log = LoggerFactory.getLogger(LichessIngestor.class);

    private final ScoreService scores;
    private final LeaderLock lock;
    private final boolean enabled;
    private final RestClient http;

    public LichessIngestor(ScoreService scores, LeaderLock lock, @Value("${app.ingest.mode}") String mode) {
        this.scores = scores;
        this.lock = lock;
        this.enabled = mode.equalsIgnoreCase("lichess") || mode.equalsIgnoreCase("hybrid");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.http = RestClient.builder()
                .baseUrl("https://lichess.org")
                .requestFactory(factory)
                .defaultHeader("Accept", "application/vnd.lichess.v3+json")
                .defaultHeader("User-Agent", "leaderboard-demo")
                .build();
    }

    @Scheduled(initialDelay = 3000, fixedDelayString = "${app.ingest.lichess-refresh-ms}")
    public void poll() {
        if (!enabled || !lock.isLeader()) return;
        for (String mode : ScoreService.MODES) {
            try {
                JsonNode root = http.get().uri("/api/player/top/50/{mode}", mode).retrieve().body(JsonNode.class);
                if (root == null) continue;
                int count = 0;
                for (JsonNode user : root.path("users")) {
                    String name = user.path("username").asText("");
                    int rating = user.path("perfs").path(mode).path("rating").asInt(0);
                    if (!name.isBlank() && rating > 0) {
                        scores.submit(new ScoreRequest(name, mode, rating, null, "lichess"));
                        count++;
                    }
                }
                log.info("Lichess {}: ingested {} players", mode, count);
            } catch (Exception e) {
                log.warn("Lichess {} fetch failed ({}), simulator will cover", mode, e.getMessage());
            }
        }
    }
}
