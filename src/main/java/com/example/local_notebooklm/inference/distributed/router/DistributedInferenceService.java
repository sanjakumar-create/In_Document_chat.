package com.example.local_notebooklm.inference.distributed.router;

import com.example.local_notebooklm.inference.distributed.balancer.LoadBalancer;
import com.example.local_notebooklm.inference.distributed.model.OllamaNode;
import com.example.local_notebooklm.inference.distributed.registry.NodeRegistry;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DistributedInferenceService {

    private static final Logger log = LoggerFactory.getLogger(DistributedInferenceService.class);

    private final NodeRegistry nodeRegistry;
    private final LoadBalancer loadBalancer;
    private final InferenceRouter inferenceRouter;
    private final boolean enabled;
    private final String configuredDefaultModel;
    private final String localConfiguredModel;

    public DistributedInferenceService(
            NodeRegistry nodeRegistry,
            LoadBalancer loadBalancer,
            InferenceRouter inferenceRouter,
            @Value("${inference.distributed.enabled:false}") boolean enabled,
            @Value("${inference.distributed.default-model:}") String configuredDefaultModel,
            @Value("${langchain4j.ollama.chat-model.model-name:}") String localConfiguredModel
    ) {
        this.nodeRegistry = nodeRegistry;
        this.loadBalancer = loadBalancer;
        this.inferenceRouter = inferenceRouter;
        this.enabled = enabled;
        this.configuredDefaultModel = configuredDefaultModel;
        this.localConfiguredModel = localConfiguredModel;
    }

    public boolean isAvailable() {
        try {
            return enabled && nodeRegistry.hasAnyHealthyNode();
        } catch (Exception ex) {
            log.warn("Distributed availability check failed: {}", ex.getMessage());
            return false;
        }
    }

    public Optional<OllamaChatModel> getModel() {
        try {
            if (!isAvailable()) {
                return Optional.empty();
            }
            List<OllamaNode> healthyNodes = nodeRegistry.getHealthyNodes();
            Optional<OllamaNode> selected = loadBalancer.select(healthyNodes);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(inferenceRouter.buildModelForNode(selected.get(), resolveModelName()));
        } catch (Exception ex) {
            log.warn("Distributed model resolution failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> chat(String jsonBody) {
        try {
            if (!isAvailable()) {
                return Optional.empty();
            }
            return Optional.of(inferenceRouter.routeChat(jsonBody));
        } catch (Exception ex) {
            log.warn("Distributed chat routing failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private String resolveModelName() {
        if (configuredDefaultModel != null && !configuredDefaultModel.isBlank()) {
            return configuredDefaultModel;
        }
        return localConfiguredModel;
    }
}
