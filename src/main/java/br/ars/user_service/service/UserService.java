package br.ars.user_service.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import br.ars.user_service.cdn.BunnyCdnClient;
import br.ars.user_service.dto.PerfilResponse;
import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.mapper.UserMapper;
import br.ars.user_service.models.User;
import br.ars.user_service.repository.UserRepository;
import br.ars.user_service.security.JwtUtil;

import jakarta.transaction.Transactional;

@Service
public class UserService {

    private final UserRepository repo;
    private final UserMapper mapper;
    private final PasswordEncoder encoder;
    private final JwtUtil jwtUtil;
    private final BunnyCdnClient bunny;

    @Value("${bunny.cdn.base-url}")
    private String cdnBaseUrl;

    public UserService(UserRepository repo, UserMapper mapper, PasswordEncoder encoder,
                       JwtUtil jwtUtil, BunnyCdnClient bunny) {
        this.repo = repo;
        this.mapper = mapper;
        this.encoder = encoder;
        this.jwtUtil = jwtUtil;
        this.bunny = bunny;
    }

    /** Registro com UUID do banco + upload do avatar com padrão: users/<primeiroNome><uuid>.<ext> */
    @Transactional
    public User register(RegisterRequest req, MultipartFile avatar) {
        // 1) unicidade
        repo.findByEmail(req.getEmail()).ifPresent(u -> {
            throw new IllegalArgumentException("Email já cadastrado.");
        });

        try {
            // 2) map + hash da senha
            User user = mapper.toEntity(req);
            if (user.getSenha() == null || user.getSenha().isBlank()) {
                throw new IllegalArgumentException("Senha obrigatória.");
            }
            user.setSenha(encoder.encode(user.getSenha()));

            // 3) persiste para gerar UUID
            user = repo.save(user);

            // 4) upload opcional -> grava URL pública final no avatarUrl
            if (avatar != null && !avatar.isEmpty()) {
                // baseName vindo do nome do usuário (primeira palavra)
                String baseName = req.getNome();
                String finalUrl = bunny.uploadAvatar(avatar, user.getId().toString(), baseName);
                user.setAvatarUrl(finalUrl);
                user = repo.save(user);
            }

            return user;

        } catch (Exception e) {
            throw new RuntimeException("Erro ao registrar usuário: " + e.getMessage(), e);
        }
    }

    public PerfilResponse getPerfilByEmail(String email) {
        User user = repo.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));

        PerfilResponse perfil = new PerfilResponse();
        perfil.setNome(user.getNome());
        perfil.setTelefone(user.getTelefone());
        perfil.setTipo(user.getTipo() != null ? user.getTipo().name() : null);
        perfil.setBio(user.getBio());
        perfil.setTags(user.getTags());
        perfil.setAvatarUrl(user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()
                ? user.getAvatarUrl() : null);
        return perfil;
    }

    public String authenticateAndGenerateToken(String email, String rawPassword) {
        User user = repo.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));
        if (!encoder.matches(rawPassword, user.getSenha())) {
            throw new RuntimeException("Senha inválida.");
        }
        return jwtUtil.generateToken(user.getId(), user.getEmail());
    }

    public Optional<User> findById(UUID id) { return repo.findById(id); }
    public Optional<User> findByEmail(String email) { return repo.findByEmail(email); }

    public void deleteUser(UUID id) {
        if (!repo.existsById(id)) throw new RuntimeException("Usuário não encontrado para deletar.");
        repo.deleteById(id);
    }
}
