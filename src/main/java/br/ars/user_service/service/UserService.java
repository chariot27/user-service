package br.ars.user_service.service;

import java.util.Hashtable;
import java.util.Optional;
import java.util.UUID;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

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

    /** Registro com UUID do banco + upload do avatar no padrão: users/<primeiroNome><uuid>.<ext> */
    @Transactional
    public User register(RegisterRequest req, MultipartFile avatar) {
        log.info("[UserService] Iniciando registro | email={} | nome={} | avatarPresente={}",
                req != null ? req.getEmail() : null,
                req != null ? req.getNome() : null,
                (avatar != null && !avatar.isEmpty()));

        // 0) valida formato e domínio MX antes de qualquer coisa
        final String rawEmail = req.getEmail();
        if (rawEmail == null || !isValidEmailFormat(rawEmail)) {
            log.warn("[UserService] E-mail inválido | rawEmail={}", rawEmail);
            throw new IllegalArgumentException("E-mail inválido.");
        }
        log.debug("[UserService] Checando MX para domínio do e-mail {}", rawEmail);
        if (!domainHasMX(rawEmail)) {
            log.warn("[UserService] Domínio sem MX | email={}", rawEmail);
            throw new IllegalArgumentException("Domínio de e-mail sem MX válido. Verifique o endereço informado.");
        }

        // normaliza e-mail ANTES da unicidade
        final String email = rawEmail.trim().toLowerCase();
        log.debug("[UserService] E-mail normalizado={}", email);

        // 1) unicidade
        repo.findByEmail(email).ifPresent(u -> {
            log.warn("[UserService] Email já cadastrado | email={}", email);
            throw new IllegalArgumentException("Email já cadastrado.");
        });

        try {
            // 2) map + hash da senha
            User user = mapper.toEntity(req);
            if (user.getSenha() == null || user.getSenha().isBlank()) {
                log.warn("[UserService] Senha ausente no request para email={}", email);
                throw new IllegalArgumentException("Senha obrigatória.");
            }
            user.setEmail(email); // já normalizado
            user.setSenha(encoder.encode(user.getSenha()));

            // 3) persiste para gerar UUID
            user = repo.save(user);
            log.info("[UserService] Usuário persistido | id={} | email={}", user.getId(), user.getEmail());

            // 4) upload opcional -> grava URL pública final no avatarUrl
            if (avatar == null) {
                log.info("[UserService] Sem avatar no request, finalizando registro sem upload.");
                return user;
            }
            if (avatar.isEmpty()) {
                log.warn("[UserService] Avatar vazio (MultipartFile.isEmpty=true), ignorando upload.");
                return user;
            }

            // baseName: usa sugestão do front (avatarUrl) se vier; senão, o nome
            String baseName = (getSafe(req.getAvatarUrl()) != null)
                    ? req.getAvatarUrl()
                    : req.getNome();

            // saneia: pega primeiro token, remove chars inválidos e baixa caixa
            String originalBaseName = baseName;
            baseName = (baseName == null ? "user" : baseName.trim());
            if (baseName.isEmpty()) baseName = "user";
            baseName = baseName.split("\\s+")[0]
                    .replaceAll("[^A-Za-z0-9_-]", "")
                    .toLowerCase();
            if (baseName.isEmpty()) baseName = "user";

            // extensão: tenta pelo content-type; senão pelo filename; default jpg
            String ext = resolveExt(avatar);

            // monta nome final e key: users/max<uuid>.<ext>
            String fileName = baseName + user.getId().toString() + "." + ext;
            String key = "users/" + fileName;

            log.info("[UserService] Upload avatar | originalBaseName={} | baseName={} | ext={} | key={}",
                    originalBaseName, baseName, ext, key);
            log.debug("[UserService] avatar.originalFilename={} | avatar.size(reported)={} | avatar.contentType={}",
                    avatar.getOriginalFilename(), avatar.getSize(), avatar.getContentType());

            // envia pro CDN usando a key (método de 2 parâmetros)
            bunny.uploadAvatar(avatar, key);

            // monta URL pública a partir da propriedade (sem hardcode)
            if (cdnBaseUrl == null || cdnBaseUrl.isBlank()) {
                log.error("[UserService] bunny.cdn.base-url não configurado! Não é possível montar avatarUrl.");
                throw new IllegalStateException("Configuração CDN ausente.");
            }
            String base = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
            String finalUrl = base + "/" + key;

            log.info("[UserService] URL pública do avatar gerada | avatarUrl={}", finalUrl);

            user.setAvatarUrl(finalUrl);
            user = repo.save(user);

            log.info("[UserService] Registro concluído | id={} | avatarUrl={}", user.getId(), user.getAvatarUrl());
            return user;

        } catch (Exception e) {
            log.error("[UserService] Erro ao registrar usuário | email={} | msg={}", email, e.getMessage(), e);
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

    private String getSafe(String s) {
        return (s != null && !s.isBlank()) ? s : null;
    }

    /** Validação simples de formato de e-mail */
    private boolean isValidEmailFormat(String email) {
        String e = email.trim();
        boolean ok = e.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
        if (!ok) log.debug("[UserService] isValidEmailFormat reprovou | email={}", email);
        return ok;
    }

    /** Checa se o domínio do e-mail possui pelo menos um registro MX (com logs) */
    private boolean domainHasMX(String email) {
        try {
            String domain = email.substring(email.indexOf('@') + 1).trim();
            if (domain.isEmpty()) {
                log.debug("[UserService] domainHasMX: domínio vazio extraído de {}", email);
                return false;
            }

            log.debug("[UserService] domainHasMX: consultando DNS para domínio={}", domain);
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000"); // ms
            env.put("com.sun.jndi.dns.timeout.retries", "1");

            DirContext ictx = new InitialDirContext(env);
            Attributes attrs = ictx.getAttributes(domain, new String[] { "MX" });
            Attribute attr = attrs.get("MX");
            if (attr != null && attr.size() > 0) {
                log.debug("[UserService] domainHasMX: MX encontrado | count={} | domain={}", attr.size(), domain);
                return true;
            }

            // fallback A/AAAA
            attrs = ictx.getAttributes(domain, new String[] { "A", "AAAA" });
            boolean hasA = (attrs.get("A") != null);
            boolean hasAAAA = (attrs.get("AAAA") != null);
            log.debug("[UserService] domainHasMX: fallback A/AAAA | A={} | AAAA={} | domain={}", hasA, hasAAAA, domain);
            return hasA || hasAAAA;

        } catch (NamingException | StringIndexOutOfBoundsException ex) {
            log.warn("[UserService] domainHasMX: exceção DNS | email={} | msg={}", email, ex.getMessage());
            return false;
        }
    }

    /** Resolve extensão com base no content-type > filename; default "jpg" (com logs) */
    private String resolveExt(MultipartFile avatar) {
        String ct = avatar.getContentType();
        if (ct != null) {
            switch (ct.toLowerCase()) {
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
        String originalFilename = avatar.getOriginalFilename();
        if (originalFilename != null && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            if (ext.matches("[a-z0-9]{1,6}")) return ext;
        }
        log.debug("[UserService] resolveExt: contentType/filename não definiram extensão; usando 'jpg'");
        return "jpg";
    }
}
