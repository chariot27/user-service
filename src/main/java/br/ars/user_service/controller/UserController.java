package br.ars.user_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import br.ars.user_service.dto.PerfilResponse;
import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.models.User;
import br.ars.user_service.registration.RegistrationCommand;
import br.ars.user_service.registration.RegistrationQueueService;
import br.ars.user_service.service.UserService;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final ObjectMapper objectMapper;
    private final UserService service;
    private final RegistrationQueueService registrationQueueService;

    // ===================== REGISTER (multipart) =====================
    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> register(
            @RequestPart("data") String data,
            @RequestPart(name = "avatar", required = false) MultipartFile avatar) {

        try {
            log.info("[Controller] /register recebido | hasAvatar={} | ct={} | size={}",
                    avatar != null && !avatar.isEmpty(),
                    avatar != null ? avatar.getContentType() : null,
                    avatar != null ? avatar.getSize() : -1);

            RegisterRequest request = objectMapper.readValue(data, RegisterRequest.class);

            // Idempotência rápida
            if (service.findByEmail(request.getEmail()).isPresent()) {
                log.info("[Controller] Email já cadastrado: {}", request.getEmail());
                return ResponseEntity.ok(Map.of("status", "already_exists", "email", request.getEmail()));
            }

            // Capture bytes AQUI (nunca enfileire MultipartFile)
            byte[] avatarBytes = null;
            String filename = null;
            String contentType = null;
            if (avatar != null && !avatar.isEmpty()) {
                avatarBytes = avatar.getBytes();
                filename = avatar.getOriginalFilename();
                contentType = avatar.getContentType();
                log.info("[Controller] Avatar capturado | filename={} | ct={} | bytes={}",
                        filename, contentType, avatarBytes.length);
            } else {
                log.info("[Controller] Sem avatar no request ou arquivo vazio.");
            }

            var cmd = new RegistrationCommand(request, avatarBytes, filename, contentType);
            boolean offered = registrationQueueService.offer(cmd);
            if (!offered) {
                log.warn("[Controller] Fila cheia. Rejeitando por backpressure.");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "status", "queue_full",
                        "message", "Sistema em pico. Tente novamente em instantes."
                ));
            }

            int size = registrationQueueService.queueSize();
            log.info("[Controller] Registro enfileirado | queueSize={}", size);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "status", "accepted",
                    "queueSize", size
            ));
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            log.warn("[Controller] JSON inválido em 'data': {}", jpe.getOriginalMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "bad_request",
                    "message", "JSON inválido no part 'data': " + jpe.getOriginalMessage()
            ));
        } catch (IllegalArgumentException iae) {
            log.warn("[Controller] Requisição inválida: {}", iae.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "bad_request",
                    "message", iae.getMessage()
            ));
        } catch (Exception ex) {
            log.error("[Controller] Falha ao enfileirar registro: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "error",
                    "message", "Falha ao enfileirar registro: " + ex.getMessage()
            ));
        }
    }

    // ===================== LOGIN =====================
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody LoginRequest body) {
        try {
            log.info("[Controller] /login | email={}", body != null ? body.email : null);
            if (body == null || body.email == null || body.password == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "bad_request",
                        "message", "Informe email e password."
                ));
            }
            String token = service.authenticateAndGenerateToken(body.email, body.password);
            log.info("[Controller] /login OK | email={}", body.email);
            return ResponseEntity.ok(Map.of("token", token));
        } catch (IllegalArgumentException iae) {
            log.warn("[Controller] /login inválido: {}", iae.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", "bad_request",
                    "message", iae.getMessage()
            ));
        } catch (Exception ex) {
            log.error("[Controller] /login erro: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", "unauthorized",
                    "message", ex.getMessage()
            ));
        }
    }

    // ===================== PERFIL POR EMAIL =====================
    @GetMapping(value = "/perfil", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getPerfilByEmail(@RequestParam("email") String email) {
        try {
            log.info("[Controller] GET /perfil | email={}", email);
            PerfilResponse perfil = service.getPerfilByEmail(email);
            return ResponseEntity.ok(perfil);
        } catch (Exception ex) {
            log.warn("[Controller] /perfil erro: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", "not_found",
                    "message", ex.getMessage()
            ));
        }
    }

    // ===================== GET BY ID (re-adicionado) =====================
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getById(@PathVariable("id") UUID id) {
        log.info("[Controller] GET /{} | id={}", "id", id);
        return service.findById(id)
                .map(UserResponse::from)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "status", "not_found",
                        "message", "Usuário não encontrado"
                )));
    }

    // ===================== EXISTS (por email) =====================
    @GetMapping(value = "/exists", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> existsByEmail(@RequestParam("email") String email) {
        boolean exists = service.findByEmail(email).isPresent();
        log.info("[Controller] /exists | email={} | exists={}", email, exists);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    // ===================== DELETE =====================
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") UUID id) {
        try {
            log.info("[Controller] DELETE /{id} | id={}", id);
            service.deleteUser(id);
            return ResponseEntity.noContent().build();
        } catch (Exception ex) {
            log.warn("[Controller] DELETE erro: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", "not_found",
                    "message", ex.getMessage()
            ));
        }
    }

    // ===================== DTOs auxiliares =====================
    public static class LoginRequest {
        public String email;
        public String password;
    }

    public static class UserResponse {
        public UUID id;
        public String nome;
        public String email;
        public String telefone;
        public String tipo;          // enum em string
        public String bio;
        public java.util.List<String> tags;
        public String avatarUrl;
        public OffsetDateTime dataCriacao;

        public static UserResponse from(User u) {
            UserResponse r = new UserResponse();
            r.id = u.getId();
            r.nome = u.getNome();
            r.email = u.getEmail();
            r.telefone = u.getTelefone();
            r.tipo = (u.getTipo() != null ? u.getTipo().name() : null);
            r.bio = u.getBio();
            r.tags = u.getTags();
            r.avatarUrl = u.getAvatarUrl();
            try {
                // se sua entidade tiver getDataCriacao()
                r.dataCriacao = (OffsetDateTime) User.class.getMethod("getDataCriacao").invoke(u);
            } catch (Exception ignore) { /* campo opcional */ }
            return r;
        }
    }
}
