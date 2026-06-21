package com.ecommerce.E_Commerce.controller;

import com.ecommerce.E_Commerce.service.ProductSeedService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin / test-data utilities. Demo-only — these endpoints write bulk data and
 * are intentionally unauthenticated for course convenience. Gate behind the
 * Nginx basic-auth (like Adminer) before any real exposure.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ProductSeedService productSeedService;

    public AdminController(ProductSeedService productSeedService) {
        this.productSeedService = productSeedService;
    }

    /**
     * Bulk-generate randomized products for benchmarking.
     * Example: {@code POST /admin/products/seed?count=5000}
     */
    @PostMapping(value = "/products/seed", produces = MediaType.TEXT_PLAIN_VALUE)
    public String seed(@RequestParam(defaultValue = "5000") int count) {
        int inserted = productSeedService.seed(count);
        long total = productSeedService.count();
        return "Seeded " + inserted + " products. Total now " + total + ".\n";
    }
}
