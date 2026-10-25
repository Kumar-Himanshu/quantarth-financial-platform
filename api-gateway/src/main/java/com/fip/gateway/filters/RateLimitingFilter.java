package com.fip.gateway.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitingFilter extends AbstractGatewayFilterFactory<RateLimitingFilter.Config> {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    // In-memory sliding window bucket tracker per client identifier
    private final Map<String, SlidingWindowCounter> clientBuckets = new ConcurrentHashMap<>();

    public RateLimitingFilter() {
        super(Config.class);
    }

    public static class Config {
        private int capacity = 100; // max requests per minute window

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String clientKey = resolveClientKey(exchange);
            SlidingWindowCounter counter = clientBuckets.computeIfAbsent(clientKey, k -> new SlidingWindowCounter());

            if (!counter.tryAcquire(config.getCapacity())) {
                log.warn("Rate limit exceeded for client: {}", clientKey);
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                response.getHeaders().add("Retry-After", "60");

                String problemJson = String.format(
                        "{\"type\":\"https://fip.com/errors/too-many-requests\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Rate limit of %d requests per minute exceeded.\",\"timestamp\":\"%s\"}",
                        config.getCapacity(),
                        Instant.now().toString()
                );
                DataBuffer buffer = response.bufferFactory().wrap(problemJson.getBytes(StandardCharsets.UTF_8));
                return response.writeWith(Mono.just(buffer));
            }

            return chain.filter(exchange);
        };
    }

    private String resolveClientKey(ServerWebExchange exchange) {
        // Prefer tenant header if present, otherwise remote IP
        String tenant = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        if (tenant != null && !tenant.isEmpty()) {
            return "tenant:" + tenant;
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown-client";
    }

    private static class SlidingWindowCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long currentMinute = System.currentTimeMillis() / 60000;

        public synchronized boolean tryAcquire(int limit) {
            long nowMinute = System.currentTimeMillis() / 60000;
            if (nowMinute != currentMinute) {
                currentMinute = nowMinute;
                count.set(0);
            }
            return count.incrementAndGet() <= limit;
        }
    }
}
