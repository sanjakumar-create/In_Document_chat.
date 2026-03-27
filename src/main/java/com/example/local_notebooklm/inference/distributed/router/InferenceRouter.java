package com.example.local_notebooklm.inference.distributed.router;

import com.example.local_notebooklm.inference.distributed.balancer.LoadBalancer;
import com.example.local_notebooklm.inference.distributed.model.OllamaNode;
import com.example.local_notebooklm.inference.distributed.registry.NodeRegistry;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class InferenceRouter {

    private static final Logger log = LoggerFactory.getLogger(InferenceRouter.class);

    private final NodeRegistry nodeRegistry;
    private final LoadBalancer loadBalancer;
    private final int maxFailures;
    private final HttpClient httpClient;

    public InferenceRouter(
            NodeRegistry nodeRegistry,
            LoadBalancer loadBalancer,
            @Value("${inference.distributed.healthcheck.max-failures:3}") int maxFailures
    ) {
        this.nodeRegistry = nodeRegistry;
        this.loadBalancer = loadBalancer;
        this.maxFailures = Math.max(1, maxFailures);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String routeChat(String ollamaJsonBody) throws InferenceException {
        OllamaNode firstNode = selectNode(nodeRegistry.getHealthyNodes())
                .orElseThrow(InferenceException::noNodesAvailable);

        try {
            return sendWithNode(firstNode, ollamaJsonBody);
        } catch (InferenceException firstError) {
            log.warn("Distributed request failed on node {}: {}", firstNode.getId(), firstError.getMessage());
            List<OllamaNode> remaining = nodeRegistry.getHealthyNodes().stream()
                    .filter(node -> !node.getId().equals(firstNode.getId()))
                    .toList();
            OllamaNode retryNode = selectNode(remaining).orElseThrow(() -> firstError);
            return sendWithNode(retryNode, ollamaJsonBody);
        }
    }

    public OllamaChatModel buildModelForNode(OllamaNode node, String modelName) {
        return OllamaChatModel.builder()
                .baseUrl(node.getBaseUrl())
                .modelName(modelName)
                .build();
    }

    private Optional<OllamaNode> selectNode(List<OllamaNode> nodes) {
        return loadBalancer.select(nodes);
    }

    private String sendWithNode(OllamaNode node, String body) {
        boolean acquired = node.tryAcquire();
        if (!acquired) {
            acquired = node.tryAcquire();
            if (!acquired) {
                throw InferenceException.allAtCapacity();
            }
        }

        node.incrementActive();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(node.getBaseUrl() + "/api/chat"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                node.markHealthy();
                return response.body();
            }

            InferenceException nodeError = InferenceException.nodeError(
                    node.getId(),
                    new IllegalStateException("HTTP " + response.statusCode())
            );
            node.recordFailure(maxFailures);
            throw nodeError;
        } catch (InferenceException ex) {
            throw ex;
        } catch (Exception ex) {
            node.recordFailure(maxFailures);
            log.warn("Distributed node error for {}: {}", node.getId(), ex.getMessage());
            throw InferenceException.nodeError(node.getId(), ex);
        } finally {
            node.release();
        }
    }
}
