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

import jakarta.transaction.Transactional;

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

    /**
     * 📥 Registro de novo usuário com validação, criptografia e mapeamento DTO.
     */
    @Transactional
    public User register(RegisterRequest req) {
        // Verifica se e-mail já está em uso
        if (repo.findByEmail(req.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email já cadastrado.");
        }

        try {
            // Mapeia o DTO para entidade
            User user = mapper.toEntity(req);

            // Criptografa a senha antes de persistir
            user.setSenha(encoder.encode(user.getSenha()));

            return repo.save(user);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao registrar usuário: " + e.getMessage(), e);
        }
    }

    /**
     * 🔐 Autentica um usuário e retorna o JWT.
     */
    public String authenticateAndGenerateToken(String email, String rawPassword) {
        User user = repo.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));

        if (!encoder.matches(rawPassword, user.getSenha())) {
            throw new RuntimeException("Senha inválida.");
        }

        return jwtUtil.generateToken(user.getId(), user.getEmail());
    }

    /**
     * 🔍 Busca usuário por ID.
     */
    public Optional<User> findById(UUID id) {
        return repo.findById(id);
    }

    /**
     * 🔍 Busca usuário por email.
     */
    public Optional<User> findByEmail(String email) {
        return repo.findByEmail(email);
    }

    /**
     * ❌ Deleta um usuário por ID.
     */
    public void deleteUser(UUID id) {
        if (!repo.existsById(id)) {
            throw new RuntimeException("Usuário não encontrado para deletar.");
        }
        repo.deleteById(id);
    }
}
