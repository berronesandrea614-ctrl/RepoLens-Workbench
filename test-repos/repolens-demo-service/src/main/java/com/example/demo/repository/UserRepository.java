package com.example.demo.repository;

import com.example.demo.entity.User;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class UserRepository {

    private final Map<Long, User> storage = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1000);

    public User findById(Long id) {
        return storage.get(id);
    }

    public User save(User user) {
        if (user.getId() == null) {
            user.setId(idGenerator.incrementAndGet());
        }
        storage.put(user.getId(), user);
        return user;
    }
}
