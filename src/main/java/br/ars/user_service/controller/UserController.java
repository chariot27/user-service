package br.ars.user_service.controller;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import br.ars.user_service.dto.LoginRequest;
import br.ars.user_service.dto.PerfilResponse;
import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.models.User;
import br.ars.user_service.registration.RegistrationCommand;
import br.ars.user_service.registration.RegistrationQueueService;
import br.ars.user_service.service.UserService;
import br.ars.user_service.queue.DbRequestQueueService; // <- serviço de fila DB-bound

@RestController
@RequestMapping("/api/users")
@CrossOrigin
public class UserController {

    private final UserService service;
    private final RegistrationQueueService registrationQueueService;
    private final DbRequestQueueService dbQueue; // <- fila para login/perfil/getById
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserController(UserService service,
                          RegistrationQueueService registrationQueueService,
                          DbRequestQueueService dbQueue) {
        this.service = service;
        this.registrationQueueService = registrationQueueService;
        this.dbQueue = dbQueue;
    }

    /**
     * Registro ASSÍNCRONO (único método de registro).
     *
     * Espera multipart:
     *  - part "data": JSON do RegisterRequest (application/json ou text/plain)
     *  - part "avatar": arquivo de imagem (opcional)
     *
     * Comportamento:
     *  - Responde 202 Accepted assim que enfileira.
     *  - Se a fila estiver cheia: 503 Service Unavailable.
     *  - Se o e-mail já existir: 200 OK (idempotente).
     */
    @PostMapping(
        value = "/register",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> register(
            @RequestPart("data") String data,
            @RequestPart(name = "avatar", required = false) MultipartFile avatar) {

        try {
            RegisterRequest request = objectMapper.readValue(data, RegisterRequest.class);

            // Idempotência rápida: se já existe, não gasta fila
            if (service.findByEmail(request.getEmail()).isPresent()) {
                return ResponseEntity.ok(Map.of(
                    "status", "already_exists",
                    "email", request.getEmail()
                ));
            }

            String requestId = UUID.randomUUID().toString();
            RegistrationCommand cmd = new RegistrationCommand(request, avatar, requestId);

            // Tenta enfileirar (sem bloquear)
            boolean offered = registrationQueueService.offer(cmd);
            if (!offered) {
                // Backpressure: protege a instância barata na nuvem
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "queue_full",
                    "message", "Sistema em pico. Tente novamente em instantes."
                ));
            }

            // Tamanho atual da fila (aproxima posição do item recém-enfileirado)
            int size = registrationQueueService.queueSize();

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "accepted",
                "requestId", requestId,
                "queueSize", size,
                "note", "O processamento inicia imediatamente se houver worker livre; caso contrário, aguarda na fila."
            ));

        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "bad_request",
                                 "message", "JSON inválido no part 'data': " + jpe.getOriginalMessage()));
        } catch (IllegalArgumentException iae) {
            // Ex.: senha vazia; evitar enfileirar lixo
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "bad_request",
                "message", iae.getMessage()
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "error",
                "message", "Falha ao enfileirar registro: " + ex.getMessage()
            ));
        }
    }

    /** Métricas da fila de registro */
    @GetMapping("/register/queue-stats")
    public ResponseEntity<Map<String, Object>> registerQueueStats() {
        return ResponseEntity.ok(Map.of(
            "queueSize", registrationQueueService.queueSize(),
            "activeDbWorkers", registrationQueueService.activeDb()
        ));
    }

    // ===================== DB-BOUND ENDPOINTS COM FILA =====================

    /** Login assíncrono (DB-bound controlado por fila) */
    @PostMapping("/login")
    public CompletableFuture<ResponseEntity<String>> login(@RequestBody LoginRequest request) {
        return dbQueue.submit(() -> {
            String token = service.authenticateAndGenerateToken(request.getEmail(), request.getPassword());
            return ResponseEntity.ok(token);
        }).exceptionally(ex -> {
            // Se a fila estiver cheia ou erro de auth, retorna 401/503 conforme mensagem
            String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            // Heurística simples: auth falhou -> 401, caso contrário 503
            HttpStatus status = (msg != null && msg.toLowerCase().contains("usuário") || (msg != null && msg.toLowerCase().contains("senha")))
                    ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(status).body(msg);
        });
    }

    /** Buscar perfil por e-mail (DB-bound) via fila */
    @GetMapping("/perfil/{email}")
    public CompletableFuture<ResponseEntity<PerfilResponse>> getPerfilByEmail(@PathVariable String email) {
        return dbQueue.submit(() -> {
            PerfilResponse perfil = service.getPerfilByEmail(email);
            return ResponseEntity.ok(perfil);
        }).exceptionally(ex -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    /** Buscar usuário por ID (DB-bound) via fila */
    @GetMapping("/{id}")
    public CompletableFuture<ResponseEntity<User>> getById(@PathVariable UUID id) {
        return dbQueue.submit(() -> service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build()))
            .exceptionally(ex -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
    }

    /** Remoção (DB-bound) – pode ou não ir para a fila; aqui deixei direto, mas dá para adaptar igual */
    @DeleteMapping("/{id}")
    public CompletableFuture<ResponseEntity<String>> delete(@PathVariable UUID id) {
        return dbQueue.submit(() -> {
            if (service.findById(id).isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Usuário não encontrado.");
            }
            service.deleteUser(id);
            return ResponseEntity.ok("Usuário removido com sucesso.");
        }).exceptionally(ex -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Falha ao remover usuário: " + ex.getMessage()));
    }

    /** Métricas da fila DB-bound (login/perfil/getById/delete) */
    @GetMapping("/db-queue-stats")
    public ResponseEntity<Map<String, Object>> dbQueueStats() {
        return ResponseEntity.ok(Map.of(
            "dbQueueSize", dbQueue.getQueueSize()
        ));
    }
}
