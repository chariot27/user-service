package br.ars.user_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.registration.RegistrationCommand;
import br.ars.user_service.registration.RegistrationQueueService;
import br.ars.user_service.service.UserService;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final ObjectMapper objectMapper;
    private final UserService service;
    private final RegistrationQueueService registrationQueueService;

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
}
