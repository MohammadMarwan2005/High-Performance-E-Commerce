package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.dto.CreateProductRequest;
import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.monitoring.Timed;
import com.ecommerce.E_Commerce.repository.ProductRepository;
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

    public Product create(CreateProductRequest request) {
        Product product = Product.builder()
                .name(request.name())
                .price(request.price())
                .stockQuantity(request.stockQuantity())
                .build();
        return productRepository.save(product);
    }

    @Timed("ProductService.findAll")
    public Page<Product> findAll(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    // Single-product read. This is the hot path the Step-6 Redis cache will
    // target with @Cacheable — a by-id lookup is the natural cache key.
    @Timed("ProductService.findById")
    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "product " + id + " not found"));
    }
}
