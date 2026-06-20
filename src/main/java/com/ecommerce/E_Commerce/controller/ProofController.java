package com.ecommerce.E_Commerce.controller;

import com.ecommerce.E_Commerce.dto.ConcurrencyProofRequest;
import com.ecommerce.E_Commerce.service.ConcurrencyProofService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand proof endpoints. Hitting these runs the proof live on this server
 * instance and returns the report as plain text — the same content the JUnit
 * harness writes to docs/req1-proof/*.txt.
 */
@RestController
@RequestMapping("/proofs")
public class ProofController {

    private final ConcurrencyProofService concurrencyProof;

    public ProofController(ConcurrencyProofService concurrencyProof) {
        this.concurrencyProof = concurrencyProof;
    }

    /**
     * Run the Requirement-1 concurrency race live.
     *
     * <p>Body is optional; any omitted field uses its default
     * (threads=50, initialStock=1, quantityEach=1, mode=both). Example:
     * <pre>{ "threads": 100, "initialStock": 1, "mode": "both" }</pre>
     */
    @PostMapping(value = "/req1/concurrency", produces = MediaType.TEXT_PLAIN_VALUE)
    public String concurrency(@RequestBody(required = false) ConcurrencyProofRequest request) {
        ConcurrencyProofRequest r =
                request == null ? new ConcurrencyProofRequest(null, null, null, null) : request;
        return concurrencyProof.run(
                r.threadsOrDefault(),
                r.initialStockOrDefault(),
                r.quantityEachOrDefault(),
                r.modeOrDefault());
    }
}
