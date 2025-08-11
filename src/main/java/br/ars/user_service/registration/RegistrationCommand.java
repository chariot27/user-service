package br.ars.user_service.registration;

import br.ars.user_service.dto.RegisterRequest;
import org.springframework.web.multipart.MultipartFile;

/** Comando para a fila de registro */
public record RegistrationCommand(
        RegisterRequest request,
        MultipartFile avatar,
        String requestId
) {}
