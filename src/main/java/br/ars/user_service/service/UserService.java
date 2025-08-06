package br.ars.user_service.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.mapper.UserMapper;
import br.ars.user_service.models.User;
import br.ars.user_service.repository.UserRepository;

@Service
public class UserService {

    private final UserRepository repo;
    private final UserMapper mapper;
    private final PasswordEncoder encoder;

    public UserService(UserRepository repo, UserMapper mapper, PasswordEncoder encoder) {
        this.repo = repo;
        this.mapper = mapper;
        this.encoder = encoder;
    }

    // REGISTRO COM CRIPTOGRAFIA
    public User register(RegisterRequest req) {
        if (repo.findByEmail(req.getEmail()).isPresent()) {
            throw new RuntimeException("Email já cadastrado.");
        }

        User user = mapper.toEntity(req);

        // Criptografar a senha ANTES de salvar no banco
        user.setSenha(encoder.encode(user.getSenha()));

        return repo.save(user);
    }

    // LOGIN (VALIDAÇÃO DE SENHA)
    public boolean validateLogin(String email, String rawPassword) {
        Optional<User> userOpt = repo.findByEmail(email);

        return userOpt
                .map(user -> encoder.matches(rawPassword, user.getSenha()))
                .orElse(false);
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
