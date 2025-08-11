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

    // id é gerado pelo banco
    @Mapping(target = "id", ignore = true)
    // avatarUrl será setado após o upload para o CDN
    @Mapping(target = "avatarUrl", ignore = true)
    // String -> Enum (UserType)
    @Mapping(target = "tipo",
        expression = "java(dto.getTipo()!=null ? br.ars.user_service.enums.UserType.valueOf(dto.getTipo()) : null)")
    User toEntity(RegisterRequest dto);

    // User -> PerfilResponse (Enum -> String)
    @Mapping(target = "tipo", expression = "java(user.getTipo()!=null ? user.getTipo().name() : null)")
    @Mapping(source = "avatarUrl", target = "avatarUrl")
    PerfilResponse toPerfilResponse(User user);
}
