package com.ecommerce.E_Commerce.service;

import com.ecommerce.E_Commerce.dto.RegisterUserRequest;
import com.ecommerce.E_Commerce.entity.User;
import com.ecommerce.E_Commerce.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User register(RegisterUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already taken");
        }
        User user = User.builder()
                .username(request.username())
                .password(request.password())
                .build();
        return userRepository.save(user);
    }
}
