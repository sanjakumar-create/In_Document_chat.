package com.example.local_notebooklm.inference.distributed.balancer;

import com.example.local_notebooklm.inference.distributed.model.OllamaNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LoadBalancer {

    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public Optional<OllamaNode> select(List<OllamaNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }

        List<OllamaNode> healthy = nodes.stream().filter(OllamaNode::isHealthy).toList();
        if (healthy.isEmpty()) {
            return Optional.empty();
        }

        List<OllamaNode> withCapacity = healthy.stream()
                .filter(node -> node.getAvailableSlots() > 0)
                .toList();

        if (!withCapacity.isEmpty()) {
            return withCapacity.stream()
                    .min(Comparator.comparingInt(OllamaNode::getActiveRequests));
        }

        int index = Math.floorMod(roundRobinIndex.getAndIncrement(), healthy.size());
        return Optional.of(healthy.get(index));
    }
}
