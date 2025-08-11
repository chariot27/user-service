package br.ars.user_service.service;

import java.util.Hashtable;
import java.util.Optional;
import java.util.UUID;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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

    /** Síncrono (controller chama direto). */
    @Transactional
    public User register(RegisterRequest req, MultipartFile avatar) {
        log.info("[UserService] Iniciando registro | email={} | nome={} | avatarPresente={}",
                req != null ? req.getEmail() : null,
                req != null ? req.getNome() : null,
                (avatar != null && !avatar.isEmpty()));

        final String rawEmail = req.getEmail();
        if (rawEmail == null || !isValidEmailFormat(rawEmail)) {
            log.warn("[UserService] E-mail inválido | rawEmail={}", rawEmail);
            throw new IllegalArgumentException("E-mail inválido.");
        }
        if (!domainHasMX(rawEmail)) {
            log.warn("[UserService] Domínio sem MX | email={}", rawEmail);
            throw new IllegalArgumentException("Domínio de e-mail sem MX válido. Verifique o endereço informado.");
        }

        final String email = rawEmail.trim().toLowerCase();
        repo.findByEmail(email).ifPresent(u -> { throw new IllegalArgumentException("Email já cadastrado."); });

        User user = mapper.toEntity(req);
        if (user.getSenha() == null || user.getSenha().isBlank()) {
            throw new IllegalArgumentException("Senha obrigatória.");
        }
        user.setEmail(email);
        user.setSenha(encoder.encode(user.getSenha()));
        user = repo.save(user);
        log.info("[UserService] Usuário persistido | id={} | email={}", user.getId(), user.getEmail());

        if (avatar == null || avatar.isEmpty()) {
            log.info("[UserService] Sem avatar (MultipartFile nulo/vazio) — finalizando registro sem upload.");
            return user;
        }

        String baseName = sanitizeBaseName(firstWordOrDefault(req.getNome(), "user"));
        String ext = resolveExt(avatar.getContentType(), avatar.getOriginalFilename());
        String key = "users/" + baseName + user.getId().toString() + "." + ext;

        log.info("[UserService] Upload avatar (multipart) | key={} | ct={} | size={}",
                key, avatar.getContentType(), avatar.getSize());

        bunny.uploadAvatar(avatar, key);

        String finalUrl = normalizedCdnBase() + "/" + key;
        user.setAvatarUrl(finalUrl);
        user = repo.save(user);
        log.info("[UserService] AvatarUrl setado={}", user.getAvatarUrl());

        return user;
    }

    /** Assíncrono (worker da fila chama este overload com BYTES). */
    @Transactional
    public User register(RegisterRequest req, byte[] avatarBytes, String filename, String contentType) {
        log.info("[UserService] Iniciando registro (BYTES) | email={} | nome={} | hasBytes={}",
                req != null ? req.getEmail() : null,
                req != null ? req.getNome() : null,
                avatarBytes != null && avatarBytes.length > 0);

        final String rawEmail = req.getEmail();
        if (rawEmail == null || !isValidEmailFormat(rawEmail)) {
            throw new IllegalArgumentException("E-mail inválido.");
        }
        if (!domainHasMX(rawEmail)) {
            throw new IllegalArgumentException("Domínio de e-mail sem MX válido. Verifique o endereço informado.");
        }

        final String email = rawEmail.trim().toLowerCase();
        repo.findByEmail(email).ifPresent(u -> { throw new IllegalArgumentException("Email já cadastrado."); });

        User user = mapper.toEntity(req);
        if (user.getSenha() == null || user.getSenha().isBlank()) {
            throw new IllegalArgumentException("Senha obrigatória.");
        }
        user.setEmail(email);
        user.setSenha(encoder.encode(user.getSenha()));
        user = repo.save(user);
        log.info("[UserService] Usuário persistido | id={} | email={}", user.getId(), user.getEmail());

        if (avatarBytes == null || avatarBytes.length == 0) {
            log.info("[UserService] Sem avatarBytes — finalizando registro sem upload.");
            return user;
        }

        String baseName = sanitizeBaseName(firstWordOrDefault(req.getNome(), "user"));
        String ext = resolveExt(contentType, filename);
        String key = "users/" + baseName + user.getId().toString() + "." + ext;

        log.info("[UserService] Upload avatar (bytes) | key={} | ct={} | bytes={}", key, contentType, avatarBytes.length);

        bunny.uploadBytes(avatarBytes, contentType, key);

        String finalUrl = normalizedCdnBase() + "/" + key;
        user.setAvatarUrl(finalUrl);
        user = repo.save(user);
        log.info("[UserService] AvatarUrl setado={}", user.getAvatarUrl());

        return user;
    }

    // ===== demais métodos da sua classe =====

    @Transactional
    public PerfilResponse getPerfilByEmail(String email) {
        User user = repo.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));

        PerfilResponse perfil = new PerfilResponse();
        perfil.setNome(user.getNome());
        perfil.setTelefone(user.getTelefone());
        perfil.setTipo(user.getTipo() != null ? user.getTipo().name() : null);
        perfil.setBio(user.getBio());
        perfil.setAvatarUrl(user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()
                ? user.getAvatarUrl() : null);
        return perfil;
    }

    @Transactional
    public String authenticateAndGenerateToken(String email, String rawPassword) {
        User user = repo.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));
        if (!encoder.matches(rawPassword, user.getSenha())) {
            throw new RuntimeException("Senha inválida.");
        }
        return jwtUtil.generateToken(user.getId(), user.getEmail());
    }

    @Transactional
    public Optional<User> findById(UUID id) { return repo.findById(id); }

    @Transactional
    public Optional<User> findByEmail(String email) { return repo.findByEmail(email); }

    @Transactional
    public void deleteUser(UUID id) {
        if (!repo.existsById(id)) throw new RuntimeException("Usuário não encontrado para deletar.");
        repo.deleteById(id);
    }

    // ===== helpers =====

    private String normalizedCdnBase() {
        if (cdnBaseUrl == null || cdnBaseUrl.isBlank()) {
            throw new IllegalStateException("bunny.cdn.base-url não configurado");
        }
        return cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
    }

    private String firstWordOrDefault(String s, String def) {
        if (s == null || s.isBlank()) return def;
        String first = s.trim().split("\\s+")[0];
        return first.isBlank() ? def : first;
    }

    private String sanitizeBaseName(String s) {
        String noAccents = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String out = noAccents.replaceAll("[^A-Za-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase();
        return out.isBlank() ? "user" : out;
    }

    private String resolveExt(String contentType, String originalFilename) {
        if (contentType != null) {
            switch (contentType.toLowerCase()) {
                case "image/png":  return "png";
                case "image/jpeg":
                case "image/jpg":  return "jpg";
                case "image/webp": return "webp";
                case "image/gif":  return "gif";
                case "image/bmp":  return "bmp";
                case "image/svg+xml": return "svg";
                case "image/heic": return "heic";
                case "image/heif": return "heif";
            }
        }
        if (originalFilename != null && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            if (ext.matches("[a-z0-9]{1,6}")) return ext;
        }
        return "jpg";
    }

    private boolean isValidEmailFormat(String email) {
        String e = email.trim();
        return e.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private boolean domainHasMX(String email) {
        try {
            String domain = email.substring(email.indexOf('@') + 1).trim();
            if (domain.isEmpty()) return false;

            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");

            DirContext ictx = new InitialDirContext(env);
            Attributes attrs = ictx.getAttributes(domain, new String[] { "MX" });
            Attribute attr = attrs.get("MX");
            if (attr != null && attr.size() > 0) return true;

            attrs = ictx.getAttributes(domain, new String[] { "A", "AAAA" });
            return (attrs.get("A") != null || attrs.get("AAAA") != null);

        } catch (NamingException | StringIndexOutOfBoundsException ex) {
            return false;
        }
    }
}
