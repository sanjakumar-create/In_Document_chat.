package com.example.local_notebooklm.inference.distributed.router;

public class InferenceException extends RuntimeException {

    private final boolean retryable;

    public InferenceException(String message) {
        this(message, null, true);
    }

    public InferenceException(String message, Throwable cause) {
        this(message, cause, true);
    }

    private InferenceException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public static InferenceException noNodesAvailable() {
        return new InferenceException("No healthy distributed nodes available", null, true);
    }

    public static InferenceException allAtCapacity() {
        return new InferenceException("All healthy distributed nodes are currently at capacity", null, true);
    }

    public static InferenceException nodeError(String nodeId, Throwable cause) {
        return new InferenceException("Distributed node request failed: " + nodeId, cause, true);
    }
}
