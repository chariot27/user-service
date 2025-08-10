package br.ars.user_service.mapper;

import br.ars.user_service.dto.PerfilResponse;
import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.models.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface UserMapper {

    // Se o ID é gerado pelo banco, ignoramos o id ao mapear do DTO
    @Mapping(target = "id", ignore = true)
    // Corrige o alvo: avatarUrl -> avatarUrl (NÃO "avatar")
    @Mapping(source = "avatarUrl", target = "avatarUrl")
    User toEntity(RegisterRequest dto);

    // Converte User -> PerfilResponse
    // Converte enum UserType para String no DTO
    @Mapping(target = "tipo", expression = "java(user.getTipo() != null ? user.getTipo().name() : null)")
    @Mapping(source = "avatarUrl", target = "avatarUrl")
    PerfilResponse toPerfilResponse(User user);
}
