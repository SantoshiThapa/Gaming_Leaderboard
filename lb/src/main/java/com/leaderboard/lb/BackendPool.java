package com.leaderboard.lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Holds the backends, picks one per request, and health-checks them every 5 s. */
@Component
public class BackendPool {

    private static final Logger log = LoggerFactory.getLogger(BackendPool.class);

    private final List<Backend> backends;
    private final boolean leastConnections;
    private final AtomicInteger rr = new AtomicInteger();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    public BackendPool(@Value("${lb.backends}") String csv, @Value("${lb.strategy}") String strategy) {
        this.backends = Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Backend::new).toList();
        this.leastConnections = strategy.equalsIgnoreCase("least-connections");
        log.info("Load balancer: {} backends, strategy={}", backends.size(), strategy);
    }

    /** Healthy backends first; if none are healthy (e.g. cold start) fall back to any not yet tried. */
    public Backend next(Set<Backend> exclude) {
        List<Backend> candidates = backends.stream().filter(b -> b.healthy && !exclude.contains(b)).toList();
        if (candidates.isEmpty()) candidates = backends.stream().filter(b -> !exclude.contains(b)).toList();
        if (candidates.isEmpty()) return null;
        int start = Math.floorMod(rr.getAndIncrement(), candidates.size());
        if (!leastConnections) return candidates.get(start);
        Backend best = null;
        for (int i = 0; i < candidates.size(); i++) {
            Backend b = candidates.get((start + i) % candidates.size());
            if (best == null || b.active.get() < best.active.get()) best = b;
        }
        return best;
    }

    @Scheduled(fixedDelay = 5000)
    public void check() {
        for (Backend b : backends) {
            boolean ok = false;
            try {
                HttpResponse<Void> resp = http.send(
                        HttpRequest.newBuilder(URI.create(b.baseUrl() + "/actuator/health"))
                                .timeout(Duration.ofSeconds(6)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                ok = resp.statusCode() == 200;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // unreachable => unhealthy
            }
            if (ok != b.healthy) log.info("Backend {} is now {}", b.name(), ok ? "UP" : "DOWN");
            b.healthy = ok;
            b.lastCheck = System.currentTimeMillis();
        }
    }

    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Backend b : backends) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("backend", b.baseUrl());
            m.put("healthy", b.healthy);
            m.put("activeConnections", b.active.get());
            m.put("requestsServed", b.served.get());
            m.put("lastCheck", b.lastCheck);
            out.add(m);
        }
        return out;
    }
}
