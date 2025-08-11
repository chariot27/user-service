package br.ars.user_service.service;

import java.util.Hashtable;
import java.util.Optional;
import java.util.UUID;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

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
    /** Registro com UUID do banco + upload do avatar com padrão: users/<primeiroNome><uuid>.<ext> */
    @Transactional
    public User register(RegisterRequest req, MultipartFile avatar) {
        // 0) valida formato e domínio MX antes de qualquer coisa
        final String rawEmail = req.getEmail();
        if (rawEmail == null || !isValidEmailFormat(rawEmail)) {
            throw new IllegalArgumentException("E-mail inválido.");
        }
        if (!domainHasMX(rawEmail)) {
            throw new IllegalArgumentException("Domínio de e-mail sem MX válido. Verifique o endereço informado.");
        }

        // normaliza e-mail ANTES da unicidade
        final String email = rawEmail.trim().toLowerCase();

        // 1) unicidade
        repo.findByEmail(email).ifPresent(u -> {
            throw new IllegalArgumentException("Email já cadastrado.");
        });

        try {
            // 2) map + hash da senha
            User user = mapper.toEntity(req);
            if (user.getSenha() == null || user.getSenha().isBlank()) {
                throw new IllegalArgumentException("Senha obrigatória.");
            }
            user.setEmail(email); // já normalizado
            user.setSenha(encoder.encode(user.getSenha()));

            // 3) persiste para gerar UUID
            user = repo.save(user);

            // 4) upload opcional -> grava URL pública final no avatarUrl
            if (avatar != null && !avatar.isEmpty()) {
                // baseName = primeiro nome saneado
                String baseName = req.getNome();
                if (baseName == null) baseName = "user";
                baseName = baseName.trim();
                if (baseName.isEmpty()) baseName = "user";
                // pega primeiro token e remove caracteres não permitidos
                baseName = baseName.split("\\s+")[0].replaceAll("[^A-Za-z0-9_-]", "").toLowerCase();
                if (baseName.isEmpty()) baseName = "user";

                String finalUrl = bunny.uploadAvatar(avatar, user.getId().toString(), baseName);
                if (finalUrl == null || finalUrl.isBlank()) {
                    throw new RuntimeException("Upload no CDN concluído, mas URL pública vazia.");
                }
                user.setAvatarUrl(finalUrl);
                user = repo.save(user);
            }

            return user;

        } catch (Exception e) {
            throw new RuntimeException("Erro ao registrar usuário: " + e.getMessage(), e);
        }
    }


    @Transactional
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

    /** Validação simples de formato de e-mail */
    private boolean isValidEmailFormat(String email) {
        String e = email.trim();
        // formato básico, sem ser overkill
        return e.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    /** Checa se o domínio do e-mail possui pelo menos um registro MX */
    private boolean domainHasMX(String email) {
        try {
            String domain = email.substring(email.indexOf('@') + 1).trim();
            if (domain.isEmpty()) return false;

            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            // timeouts para não travar request se o DNS estiver lento
            env.put("com.sun.jndi.dns.timeout.initial", "2000"); // ms
            env.put("com.sun.jndi.dns.timeout.retries", "1");

            DirContext ictx = new InitialDirContext(env);
            Attributes attrs = ictx.getAttributes(domain, new String[] { "MX" });
            Attribute attr = attrs.get("MX");
            if (attr != null && attr.size() > 0) return true;

            // fallback: alguns domínios não expõem MX e usam A/AAAA para SMTP (raro, mas possível)
            attrs = ictx.getAttributes(domain, new String[] { "A", "AAAA" });
            return (attrs.get("A") != null || attrs.get("AAAA") != null);

        } catch (NamingException | StringIndexOutOfBoundsException ex) {
            return false;
        }
    }
}
