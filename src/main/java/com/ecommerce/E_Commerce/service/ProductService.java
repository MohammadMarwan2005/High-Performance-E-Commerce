package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.config.CacheConfig;
import com.ecommerce.E_Commerce.dto.CreateProductRequest;
import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.monitoring.Timed;
import com.ecommerce.E_Commerce.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // A new product can appear on any browse listing, so wipe the page cache.
    // (No productById entry exists for an id that was just generated.)
    @CacheEvict(cacheNames = CacheConfig.PRODUCT_PAGES, allEntries = true)
    public Product create(CreateProductRequest request) {
        Product product = Product.builder()
                .name(request.name())
                .price(request.price())
                .stockQuantity(request.stockQuantity())
                .build();
        return productRepository.save(product);
    }

    // Req 6 — browse listing cache. Keyed by the (page, size, sort) tuple so each
    // distinct page is its own entry. Short-TTL (bounded staleness) by design:
    // the ~50 hot page queries are the Step-5 bottleneck, and precise eviction of
    // every page permutation on each stock change is impractical. See CacheConfig.
    @Cacheable(cacheNames = CacheConfig.PRODUCT_PAGES,
            key = "#pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    @Timed("ProductService.findAll")
    public Page<Product> findAll(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    // Req 6 — authoritative single-product read, cached by id. Strongly consistent:
    // OrderService evicts this entry whenever a checkout changes the product's
    // stock, so the detail read is never stale. A by-id lookup is the natural key.
    @Cacheable(cacheNames = CacheConfig.PRODUCT_BY_ID, key = "#id")
    @Timed("ProductService.findById")
    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "product " + id + " not found"));
    }
}
