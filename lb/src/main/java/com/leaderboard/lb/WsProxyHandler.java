package com.leaderboard.lb;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Browser <-> LB <-> API WebSocket proxy: one upstream connection per browser connection. */
public class WsProxyHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WsProxyHandler.class);

    private record Conn(WebSocketSession upstream, Backend backend) {}

    private final BackendPool pool;
    private final StandardWebSocketClient upstreamClient;
    private final Map<String, Conn> connections = new ConcurrentHashMap<>();

    public WsProxyHandler(BackendPool pool) {
        this.pool = pool;
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(512 * 1024);
        this.upstreamClient = new StandardWebSocketClient(container);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession client) throws Exception {
        Set<Backend> tried = new HashSet<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            Backend backend = pool.next(tried);
            if (backend == null) break;
            tried.add(backend);
            backend.active.incrementAndGet();
            try {
                WebSocketSession upstream = upstreamClient
                        .execute(new Upstream(client), new WebSocketHttpHeaders(), URI.create(backend.wsBase() + "/ws"))
                        .get(15, TimeUnit.SECONDS);
                connections.put(client.getId(), new Conn(upstream, backend));
                return;
            } catch (Exception e) {
                backend.active.decrementAndGet();
                backend.healthy = false;
                log.warn("WebSocket upstream {} failed: {}", backend.name(), e.getMessage());
            }
        }
        client.close(CloseStatus.SERVICE_RESTARTED);
    }

    @Override
    protected void handleTextMessage(WebSocketSession client, TextMessage message) throws Exception {
        Conn conn = connections.get(client.getId());
        if (conn != null && conn.upstream().isOpen()) {
            synchronized (conn.upstream()) {
                conn.upstream().sendMessage(message);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession client, CloseStatus status) {
        Conn conn = connections.remove(client.getId());
        if (conn == null) return;
        conn.backend().active.decrementAndGet();
        try {
            if (conn.upstream().isOpen()) conn.upstream().close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /** Upstream -> browser direction. */
    private static class Upstream extends TextWebSocketHandler {
        private final WebSocketSession client;

        Upstream(WebSocketSession client) {
            this.client = client;
        }

        @Override
        protected void handleTextMessage(WebSocketSession upstream, TextMessage message) throws Exception {
            synchronized (client) {
                if (client.isOpen()) client.sendMessage(message);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession upstream, CloseStatus status) {
            try {
                if (client.isOpen()) client.close(CloseStatus.GOING_AWAY);
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
