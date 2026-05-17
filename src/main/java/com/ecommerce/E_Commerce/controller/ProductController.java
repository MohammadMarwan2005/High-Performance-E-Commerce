package com.ecommerce.E_Commerce.controller;

import com.ecommerce.E_Commerce.dto.CreateProductRequest;
import com.ecommerce.E_Commerce.dto.ProductResponse;
import com.ecommerce.E_Commerce.entity.Product;
import com.ecommerce.E_Commerce.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        Product saved = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(saved));
    }

    @GetMapping
    public List<ProductResponse> list() {
        return productService.findAll().stream().map(ProductResponse::from).toList();
    }
}
