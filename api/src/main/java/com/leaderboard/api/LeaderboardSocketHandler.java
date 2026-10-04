package com.leaderboard.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes top-50 snapshots to browsers. Redis Pub/Sub messages only mark a mode "dirty";
 * a 400 ms flush coalesces bursts so thousands of writes/second don't flood clients.
 */
@Component
public class LeaderboardSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final RankingService ranking;
    private final ObjectMapper mapper;
    private final String instanceId;

    public LeaderboardSocketHandler(RankingService ranking, ObjectMapper mapper,
                                    @Value("${app.instance-id}") String instanceId) {
        this.ranking = ranking;
        this.mapper = mapper;
        this.instanceId = instanceId;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5000, 256 * 1024);
        sessions.put(session.getId(), safe);
        for (String mode : ScoreService.MODES) {
            send(safe, new TextMessage(snapshot(mode)));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
    }

    public void markDirty(String mode) {
        if (ScoreService.MODES.contains(mode)) dirty.add(mode);
    }

    @Scheduled(fixedDelay = 400)
    public void flush() {
        if (sessions.isEmpty()) {
            dirty.clear();
            return;
        }
        for (String mode : new ArrayList<>(dirty)) {
            dirty.remove(mode);
            broadcast(snapshot(mode));
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        broadcast("{\"type\":\"ping\"}");
    }

    private String snapshot(String mode) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "type", "snapshot",
                    "mode", mode,
                    "ts", System.currentTimeMillis(),
                    "instance", instanceId,
                    "entries", ranking.top(mode, 50)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void broadcast(String json) {
        TextMessage message = new TextMessage(json);
        for (WebSocketSession s : sessions.values()) {
            send(s, message);
        }
    }

    private void send(WebSocketSession s, TextMessage message) {
        try {
            if (s.isOpen()) s.sendMessage(message);
        } catch (Exception e) {
            sessions.values().remove(s);
            try {
                s.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
