package com.leaderboard.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/** Built-in fallback: seeds fake players when a board is nearly empty, then keeps nudging ratings so the UI stays live. */
@Component
public class SimulatorService {

    private static final Logger log = LoggerFactory.getLogger(SimulatorService.class);
    private static final String[] ADJ = {"Neon", "Turbo", "Pixel", "Cosmic", "Shadow", "Blaze", "Vapor", "Glitch",
            "Nova", "Rogue", "Hyper", "Lunar", "Toxic", "Cyber", "Echo"};
    private static final String[] NOUN = {"Fox", "Wolf", "Knight", "Ghost", "Falcon", "Viper", "Rook", "Pawn",
            "Bishop", "Comet", "Raven", "Tiger", "Panda", "Storm", "Ninja"};

    private final ScoreService scores;
    private final RankingService ranking;
    private final LeaderLock lock;
    private final boolean enabled;
    private final Random rnd = new Random();

    public SimulatorService(ScoreService scores, RankingService ranking, LeaderLock lock,
                            @Value("${app.ingest.mode}") String mode) {
        this.scores = scores;
        this.ranking = ranking;
        this.lock = lock;
        this.enabled = mode.equalsIgnoreCase("simulator") || mode.equalsIgnoreCase("hybrid");
    }

    @Scheduled(initialDelay = 20000, fixedDelayString = "${app.ingest.sim-interval-ms}")
    public void tick() {
        if (!enabled || !lock.isLeader()) return;
        String mode = ScoreService.MODES.get(rnd.nextInt(ScoreService.MODES.size()));
        if (ranking.size(mode) < 30) {
            seed(mode);
            return;
        }
        List<Entry> pool = ranking.top(mode, 100);
        int moves = 1 + rnd.nextInt(3);
        for (int i = 0; i < moves; i++) {
            Entry e = pool.get(rnd.nextInt(pool.size()));
            int delta = (int) Math.round(rnd.nextGaussian() * 14);
            if (rnd.nextInt(20) == 0) delta = (rnd.nextBoolean() ? 1 : -1) * (40 + rnd.nextInt(60)); // big swing
            if (delta == 0) delta = 1;
            scores.submit(new ScoreRequest(e.username(), mode, null, delta, "sim"));
        }
    }

    private void seed(String mode) {
        log.info("Seeding simulated players for {}", mode);
        for (int i = 0; i < 60; i++) {
            String name = ADJ[rnd.nextInt(ADJ.length)] + NOUN[rnd.nextInt(NOUN.length)] + (10 + rnd.nextInt(90));
            int rating = (int) Math.round(1500 + rnd.nextGaussian() * 280);
            scores.submit(new ScoreRequest(name, mode, rating, null, "sim"));
        }
    }
}
