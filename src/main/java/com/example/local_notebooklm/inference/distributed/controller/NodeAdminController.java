package com.example.local_notebooklm.inference.distributed.controller;

import com.example.local_notebooklm.inference.distributed.model.NodeStatus;
import com.example.local_notebooklm.inference.distributed.model.OllamaNode;
import com.example.local_notebooklm.inference.distributed.registry.NodeRegistry;
import com.example.local_notebooklm.inference.distributed.router.DistributedInferenceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/inference/nodes")
public class NodeAdminController {

    private final NodeRegistry nodeRegistry;
    private final DistributedInferenceService distributedInferenceService;
    private final boolean adminEnabled;
    private final int maxFailures;
    private final HttpClient httpClient;

    public NodeAdminController(
            NodeRegistry nodeRegistry,
            DistributedInferenceService distributedInferenceService,
            @Value("${inference.distributed.admin.enabled:false}") boolean adminEnabled,
            @Value("${inference.distributed.healthcheck.max-failures:3}") int maxFailures
    ) {
        this.nodeRegistry = nodeRegistry;
        this.distributedInferenceService = distributedInferenceService;
        this.adminEnabled = adminEnabled;
        this.maxFailures = Math.max(1, maxFailures);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @GetMapping({"", "/"})
    public ResponseEntity<List<NodeStatus>> getAll() {
        if (!adminEnabled) {
            return ResponseEntity.notFound().build();
        }
        List<NodeStatus> statuses = nodeRegistry.getAllNodes().stream().map(OllamaNode::toStatus).toList();
        return ResponseEntity.ok(statuses);
    }

    @GetMapping("/healthy")
    public ResponseEntity<List<NodeStatus>> getHealthy() {
        if (!adminEnabled) {
            return ResponseEntity.notFound().build();
        }
        List<NodeStatus> statuses = nodeRegistry.getHealthyNodes().stream().map(OllamaNode::toStatus).toList();
        return ResponseEntity.ok(statuses);
    }

    @GetMapping("/availability")
    public ResponseEntity<String> availability() {
        if (!adminEnabled) {
            return ResponseEntity.notFound().build();
        }
        if (distributedInferenceService.isAvailable()) {
            return ResponseEntity.ok("AVAILABLE");
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("UNAVAILABLE");
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(
            @RequestParam String id,
            @RequestParam("url") String url,
            @RequestParam(defaultValue = "2") int maxConcurrent
    ) {
        if (!adminEnabled) {
            return ResponseEntity.notFound().build();
        }
        nodeRegistry.registerNode(id, url, maxConcurrent);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!adminEnabled) {
            return ResponseEntity.notFound().build();
        }
        Optional<OllamaNode> node = nodeRegistry.getAllNodes().stream()
                .filter(n -> n.getId().equals(id))
                .findFirst();
        if (node.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        nodeRegistry.deregisterNode(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/test/{id}")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id) {
        if (!adminEnabled) {
            return ResponseEntity.notFound().build();
        }

        Optional<OllamaNode> nodeOpt = nodeRegistry.getAllNodes().stream()
                .filter(node -> node.getId().equals(id))
                .findFirst();

        if (nodeOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        OllamaNode node = nodeOpt.get();
        long start = System.currentTimeMillis();
        boolean ok = ping(node);
        long latency = System.currentTimeMillis() - start;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("latencyMs", latency);

        if (ok) {
            node.markHealthy();
            response.put("status", node.toStatus());
            response.put("reachable", true);
            return ResponseEntity.ok(response);
        }

        node.recordFailure(maxFailures);
        response.put("status", node.toStatus());
        response.put("reachable", false);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
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
}
