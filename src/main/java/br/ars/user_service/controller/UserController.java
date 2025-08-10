package br.ars.user_service.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
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

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping(value = "/register", consumes = "multipart/form-data")
    public ResponseEntity<User> register(
            @RequestPart("data") RegisterRequest request,
            @RequestPart(name = "avatar", required = false) MultipartFile avatar) {

        User user = service.register(request, avatar);
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getById(@PathVariable UUID id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Novo endpoint para buscar perfil por e-mail */
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
