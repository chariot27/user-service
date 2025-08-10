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

    /** 📥 Registro com UUID gerado pelo banco + upload usando nome enviado no JSON */
    @Transactional
    public User register(RegisterRequest req, MultipartFile avatar) {
        if (repo.findByEmail(req.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email já cadastrado.");
        }

        try {
            // 1) mapear DTO -> entidade e criptografar senha
            User user = mapper.toEntity(req);
            user.setSenha(encoder.encode(user.getSenha()));

            // 2) salvar primeiro para gerar o ID (UUID do banco/JPA)
            user = repo.save(user); // saveAndFlush(user) se precisar do ID imediatamente

            // 3) se veio avatar, usar o NOME do JSON (req.getAvatarUrl) como base
            if (avatar != null && !avatar.isEmpty()) {
                String ext = guessExt(avatar.getOriginalFilename(), avatar.getContentType());
                if (ext == null) ext = "jpg";

                String desired = req.getAvatarUrl(); // no JSON vem o nome desejado
                String baseName = sanitizeFileBase(desired != null ? desired : user.getId().toString());

                // remove extensão caso o cliente tenha mandado
                int dot = baseName.lastIndexOf('.');
                if (dot > 0) baseName = baseName.substring(0, dot);

                String finalFileName = baseName + "." + ext;   // ex.: "perfil_do_joao.webp"
                String subfolder = user.getId().toString();    // users/{id}/

                String returned = bunny.uploadWithName(avatar, subfolder, finalFileName);

                // Normaliza p/ URL completa se por acaso voltar relativo
                String base = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
                String finalUrl = (returned != null && returned.startsWith("http"))
                        ? returned
                        : base + "/" + (returned != null && returned.startsWith("/") ? returned.substring(1) : returned);

                user.setAvatarUrl(finalUrl);
                user = repo.save(user);
            }

            return user;

        } catch (Exception e) {
            throw new RuntimeException("Erro ao registrar usuário: " + e.getMessage(), e);
        }
    }

    /** 🔍 Perfil por e-mail com avatar vindo do CDN */
    public PerfilResponse getPerfilByEmail(String email) {
        User user = repo.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));

        PerfilResponse perfil = new PerfilResponse();
        perfil.setNome(user.getNome());
        perfil.setTelefone(user.getTelefone());
        perfil.setTipo(user.getTipo() != null ? user.getTipo().name() : null);
        perfil.setBio(user.getBio());
        perfil.setTags(user.getTags());

        if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
            if (user.getAvatarUrl().startsWith("http")) {
                perfil.setAvatarUrl(user.getAvatarUrl());
            } else {
                String base = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
                perfil.setAvatarUrl(base + (user.getAvatarUrl().startsWith("/") ? user.getAvatarUrl() : "/" + user.getAvatarUrl()));
            }
        } else {
            perfil.setAvatarUrl(null);
        }

        return perfil;
    }

    /** 🔐 Login (inalterado) */
    public String authenticateAndGenerateToken(String email, String rawPassword) {
        User user = repo.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));

        if (!encoder.matches(rawPassword, user.getSenha())) {
            throw new RuntimeException("Senha inválida.");
        }

        return jwtUtil.generateToken(user.getId(), user.getEmail());
    }

    /** 🔍 Busca por ID (nome mantido) */
    public Optional<User> findById(UUID id) {
        return repo.findById(id);
    }

    /** 🔍 Busca por e-mail */
    public Optional<User> findByEmail(String email) {
        return repo.findByEmail(email);
    }

    /** ❌ Deleta por ID */
    public void deleteUser(UUID id) {
        if (!repo.existsById(id)) {
            throw new RuntimeException("Usuário não encontrado para deletar.");
        }
        repo.deleteById(id);
    }

    /* ===== helpers ===== */

    private String sanitizeFileBase(String input) {
        if (input == null || input.isBlank()) return "avatar";
        String base = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")              // remove acentos
                .replaceAll("[^a-zA-Z0-9-_.]", "_")     // não-alfanum => _
                .replaceAll("_+", "_")                  // colapsa _
                .replaceAll("(^_|_$)", "")              // trim _
                .toLowerCase();
        return base.isBlank() ? "avatar" : base;
    }

    private String guessExt(String original, String contentType) {
        if (original != null && original.contains(".")) {
            String ex = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
            if (ex.matches("[a-z0-9]{1,5}")) return ex;
        }
        if (contentType == null) return null;
        switch (contentType) {
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            default: return null;
        }
    }
}
