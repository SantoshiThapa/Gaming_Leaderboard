package com.leaderboard.lb;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Forwards /api/** to a backend chosen by the pool. Retries once on another node if the first is unreachable. */
@RestController
public class HttpProxyController {

    private static final Set<String> SKIP_REQUEST = Set.of("host", "content-length", "connection", "expect",
            "upgrade", "transfer-encoding", "keep-alive", "te", "trailer", "proxy-authorization",
            "proxy-authenticate", "accept-encoding");
    private static final Set<String> SKIP_RESPONSE = Set.of("content-length", "transfer-encoding", "connection",
            "keep-alive", "upgrade", "trailer");

    private final BackendPool pool;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public HttpProxyController(BackendPool pool) {
        this.pool = pool;
    }

    @GetMapping("/lb/status")
    public List<Map<String, Object>> status() {
        return pool.status();
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest req) throws IOException {
        byte[] body = req.getInputStream().readAllBytes();
        String target = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        String method = req.getMethod();
        boolean idempotent = method.equals("GET") || method.equals("HEAD");
        Set<Backend> tried = new HashSet<>();

        for (int attempt = 0; attempt < 2; attempt++) {
            Backend backend = pool.next(tried);
            if (backend == null) break;
            tried.add(backend);
            backend.active.incrementAndGet();
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(backend.baseUrl() + target))
                        .timeout(Duration.ofSeconds(30));
                rb.method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
                for (String name : Collections.list(req.getHeaderNames())) {
                    if (SKIP_REQUEST.contains(name.toLowerCase())) continue;
                    for (String value : Collections.list(req.getHeaders(name))) {
                        try {
                            rb.header(name, value);
                        } catch (IllegalArgumentException ignored) {
                            // restricted header, skip
                        }
                    }
                }
                HttpResponse<byte[]> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                backend.served.incrementAndGet();
                HttpHeaders out = new HttpHeaders();
                resp.headers().map().forEach((k, v) -> {
                    if (!SKIP_RESPONSE.contains(k.toLowerCase())) out.addAll(k, v);
                });
                out.set("X-Backend", backend.name());
                return ResponseEntity.status(resp.statusCode()).headers(out).body(resp.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                backend.healthy = false;
                boolean connectFailure = e instanceof ConnectException || e instanceof HttpConnectTimeoutException;
                if (!idempotent && !connectFailure) break;
            } finally {
                backend.active.decrementAndGet();
            }
        }
        return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"no healthy backend\"}".getBytes(StandardCharsets.UTF_8));
    }
}
