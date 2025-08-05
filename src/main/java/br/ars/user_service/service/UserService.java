package br.ars.user_service.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.mapper.UserMapper;
import br.ars.user_service.models.User;
import br.ars.user_service.repository.UserRepository;

@Service
public class UserService {

    private final UserRepository repo;
    private final UserMapper mapper;

    public UserService(UserRepository repo, UserMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public User register(RegisterRequest req) {
        if (repo.findByEmail(req.email).isPresent()) {
            throw new RuntimeException("Email já cadastrado.");
        }
        User user = mapper.toEntity(req);
        return repo.save(user);
    }

    public Optional<User> findById(UUID id) {
        return repo.findById(id);
    }

    public Optional<User> findByEmail(String email) {
        return repo.findByEmail(email);
    }

    public void deleteUser(UUID id) {
        repo.deleteById(id);
    }
}
