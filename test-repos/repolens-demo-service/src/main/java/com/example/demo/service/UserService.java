package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getUserById(Long id) {
        User user = userRepository.findById(id);
        if (user != null) {
            return user;
        }
        return new User(id, "mock-user-" + id, "mock-" + id + "@example.com");
    }

    public User createUser(User user) {
        return userRepository.save(user);
    }
}
