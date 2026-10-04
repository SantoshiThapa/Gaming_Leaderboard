package com.leaderboard.lb;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Backend {
    private final String baseUrl;
    private final String name;
    public final AtomicInteger active = new AtomicInteger();
    public final AtomicLong served = new AtomicLong();
    public volatile boolean healthy = false;
    public volatile long lastCheck = 0;

    public Backend(String url) {
        this.baseUrl = url.trim().replaceAll("/+$", "");
        String host = URI.create(this.baseUrl).getHost();
        this.name = host == null ? this.baseUrl : host;
    }

    public String baseUrl() { return baseUrl; }

    public String name() { return name; }

    /** http -> ws, https -> wss */
    public String wsBase() { return baseUrl.replaceFirst("^http", "ws"); }
}
