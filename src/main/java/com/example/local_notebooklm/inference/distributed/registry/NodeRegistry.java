package com.example.local_notebooklm.inference.distributed.registry;

import com.example.local_notebooklm.inference.distributed.model.OllamaNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final CopyOnWriteArrayList<OllamaNode> nodes = new CopyOnWriteArrayList<>();
    private final List<String> configuredNodes;

    public NodeRegistry(@Value("${inference.distributed.nodes:}") List<String> configuredNodes) {
        this.configuredNodes = configuredNodes != null ? configuredNodes : Collections.emptyList();
    }

    @PostConstruct
    public void initialize() {
        for (String entry : configuredNodes) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split("\\|");
            if (parts.length != 3) {
                log.warn("Skipping invalid distributed node entry: {}", entry);
                continue;
            }

            String id = parts[0].trim();
            String baseUrl = parts[1].trim();
            int maxConcurrent;
            try {
                maxConcurrent = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException ex) {
                log.warn("Skipping node entry with invalid maxConcurrent: {}", entry);
                continue;
            }

            if (id.isBlank() || baseUrl.isBlank()) {
                log.warn("Skipping node entry with blank id/url: {}", entry);
                continue;
            }
            registerNode(id, baseUrl, maxConcurrent);
        }

        if (nodes.isEmpty()) {
            log.info("Distributed inference node registry initialized with zero nodes");
        }
    }

    public void registerNode(String id, String baseUrl, int maxConcurrent) {
        deregisterNode(id);
        nodes.add(new OllamaNode(id.trim(), normalizeBaseUrl(baseUrl), Math.max(1, maxConcurrent)));
    }

    public void deregisterNode(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        String normalizedId = id.trim();
        nodes.removeIf(node -> node.getId().equals(normalizedId));
    }

    public List<OllamaNode> getAllNodes() {
        return new ArrayList<>(nodes);
    }

    public List<OllamaNode> getHealthyNodes() {
        return nodes.stream().filter(OllamaNode::isHealthy).toList();
    }

    public boolean hasAnyHealthyNode() {
        return nodes.stream().anyMatch(OllamaNode::isHealthy);
    }

    private String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
