package com.ecommerce.E_Commerce.controller;

import com.ecommerce.E_Commerce.dto.RegisterUserRequest;
import com.ecommerce.E_Commerce.entity.User;
import com.ecommerce.E_Commerce.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisteredUserResponse> register(@Valid @RequestBody RegisterUserRequest request) {
        User saved = userService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new RegisteredUserResponse(saved.getId(), saved.getUsername()));
    }

    public record RegisteredUserResponse(Long id, String username) {}
}
