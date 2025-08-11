package br.ars.user_service.controller;

import java.util.UUID;

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
import br.ars.user_service.service.UserService;

@RestController
@RequestMapping("/api/users")
@CrossOrigin
public class UserController {

    private final UserService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserController(UserService service) {
        this.service = service;
    }

    /**
     * Espera multipart:
     *  - part "data": JSON do RegisterRequest (enviado como application/json ou text/plain)
     *  - part "avatar": arquivo de imagem (opcional)
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
            User user = service.register(request, avatar);
            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("JSON inválido no part 'data': " + jpe.getOriginalMessage());
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(iae.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Falha ao registrar usuário: " + ex.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getById(@PathVariable UUID id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Buscar perfil por e-mail */
    @GetMapping("/perfil/{email}")
    public ResponseEntity<PerfilResponse> getPerfilByEmail(@PathVariable String email) {
        try {
            PerfilResponse perfil = service.getPerfilByEmail(email);
            return ResponseEntity.ok(perfil);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        if (service.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Usuário não encontrado.");
        }
        service.deleteUser(id);
        return ResponseEntity.ok("Usuário removido com sucesso.");
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody LoginRequest request) {
        try {
            String token = service.authenticateAndGenerateToken(request.getEmail(), request.getPassword());
            return ResponseEntity.ok(token);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
        }
    }
}
