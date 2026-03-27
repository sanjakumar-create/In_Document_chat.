package com.example.local_notebooklm.inference.distributed.router;

import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class InferenceServiceAdapter {

    private final DistributedInferenceService distributed;

    public InferenceServiceAdapter(DistributedInferenceService distributed) {
        this.distributed = distributed;
    }

    /**
     * Returns a chat model — distributed node if available,
     * else empty so caller uses its own local Ollama model.
     * NEVER throws.
     */
    public Optional<OllamaChatModel> resolveModel() {
        return distributed.getModel();
    }

    /**
     * Returns true if distributed inference is active and healthy.
     * Caller uses this to decide routing.
     */
    public boolean isDistributedAvailable() {
        return distributed.isAvailable();
    }
}
