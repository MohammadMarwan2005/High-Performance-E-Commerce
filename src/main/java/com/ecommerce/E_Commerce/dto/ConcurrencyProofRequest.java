package com.ecommerce.E_Commerce.dto;

/**
 * Tunable parameters for the live Req-1 concurrency proof
 * (POST /proofs/req1/concurrency). All fields are optional — any null falls
 * back to the documented default. Bounds are clamped server-side so the
 * endpoint can't be used to exhaust the host.
 */
public record ConcurrencyProofRequest(
        Integer threads,
        Integer initialStock,
        Integer quantityEach,
        String mode) {

    public static final int DEFAULT_THREADS = 50;
    public static final int DEFAULT_INITIAL_STOCK = 1;
    public static final int DEFAULT_QUANTITY_EACH = 1;
    public static final String DEFAULT_MODE = "both"; // both | naive | locked | pessimistic

    public static final int MAX_THREADS = 500;

    public int threadsOrDefault() {
        int t = threads == null ? DEFAULT_THREADS : threads;
        return Math.max(1, Math.min(t, MAX_THREADS));
    }

    public int initialStockOrDefault() {
        int s = initialStock == null ? DEFAULT_INITIAL_STOCK : initialStock;
        return Math.max(0, s);
    }

    public int quantityEachOrDefault() {
        int q = quantityEach == null ? DEFAULT_QUANTITY_EACH : quantityEach;
        return Math.max(1, q);
    }

    public String modeOrDefault() {
        if (mode == null) return DEFAULT_MODE;
        String m = mode.trim().toLowerCase();
        return switch (m) {
            case "naive", "locked", "pessimistic", "both" -> m;
            default -> DEFAULT_MODE;
        };
    }
}
