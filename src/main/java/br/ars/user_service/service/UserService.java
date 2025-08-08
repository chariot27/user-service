package br.ars.user_service.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.models.User;
import br.ars.user_service.repository.UserRepository;
import br.ars.user_service.mapper.UserMapper;
import br.ars.user_service.security.JwtUtil;

@Service
public class UserService {

    private final UserRepository repo;
    private final UserMapper mapper;
    private final PasswordEncoder encoder;
    private final JwtUtil jwtUtil;

    public UserService(UserRepository repo, UserMapper mapper, PasswordEncoder encoder, JwtUtil jwtUtil) {
        this.repo = repo;
        this.mapper = mapper;
        this.encoder = encoder;
        this.jwtUtil = jwtUtil;
    }

    // REGISTRO COM CRIPTOGRAFIA
    public User register(RegisterRequest req) {
        if (repo.findByEmail(req.getEmail()).isPresent()) {
            throw new RuntimeException("Email já cadastrado.");
        }

        User user = mapper.toEntity(req);
        user.setSenha(encoder.encode(user.getSenha()));
        return repo.save(user);
    }

    // AUTENTICAÇÃO + GERAÇÃO DE TOKEN
    public String authenticateAndGenerateToken(String email, String rawPassword) {
        Optional<User> userOpt = repo.findByEmail(email);

        User user = userOpt.orElseThrow(() -> new RuntimeException("Usuário não encontrado."));

        if (!encoder.matches(rawPassword, user.getSenha())) {
            throw new RuntimeException("Senha inválida.");
        }

        // Geração do token JWT com o ID e e-mail do usuário
        return jwtUtil.generateToken(user.getId(), user.getEmail());
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
