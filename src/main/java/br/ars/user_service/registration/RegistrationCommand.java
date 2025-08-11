package br.ars.user_service.registration;

import br.ars.user_service.dto.RegisterRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RegistrationCommand {
    private final RegisterRequest request;
    private final byte[] avatarBytes;     // pode ser null
    private final String filename;        // pode ser null
    private final String contentType;     // pode ser null
}
