package com.example.local_notebooklm.inference.distributed.health;

import com.example.local_notebooklm.inference.distributed.model.OllamaNode;
import com.example.local_notebooklm.inference.distributed.registry.NodeRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    private final NodeRegistry nodeRegistry;
    private final int intervalSeconds;
    private final int maxFailures;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Boolean> lastKnownHealth = new ConcurrentHashMap<>();

    public HealthCheckService(
            NodeRegistry nodeRegistry,
            @Value("${inference.distributed.healthcheck.interval-seconds:10}") int intervalSeconds,
            @Value("${inference.distributed.healthcheck.max-failures:3}") int maxFailures
    ) {
        this.nodeRegistry = nodeRegistry;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.maxFailures = Math.max(1, maxFailures);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::checkAllNodes, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void checkAllNodes() {
        var nodes = nodeRegistry.getAllNodes();
        if (nodes.isEmpty()) {
            return;
        }

        for (OllamaNode node : nodes) {
            boolean wasHealthy = node.isHealthy();
            boolean isHealthyNow = ping(node);

            if (isHealthyNow) {
                node.markHealthy();
            } else {
                node.recordFailure(maxFailures);
            }

            Boolean previous = lastKnownHealth.put(node.getId(), node.isHealthy());
            boolean previousState = previous != null ? previous : wasHealthy;
            boolean becameHealthy = !previousState && node.isHealthy();
            boolean becameUnhealthy = previousState && !node.isHealthy();

            if (becameUnhealthy) {
                log.warn("Distributed node DOWN: {} ({})", node.getId(), node.getBaseUrl());
            } else if (becameHealthy) {
                log.info("Distributed node recovered: {} ({})", node.getId(), node.getBaseUrl());
            }
        }
    }

    private boolean ping(OllamaNode node) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(node.getBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception ex) {
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
