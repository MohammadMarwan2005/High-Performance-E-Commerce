package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.dto.CreateProductRequest;
import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.monitoring.Timed;
import com.ecommerce.E_Commerce.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;

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
    public List<Product> findAll() {
        return productRepository.findAll();
    }
}
