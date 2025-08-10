package br.ars.user_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import br.ars.user_service.dto.PerfilResponse;
import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.models.User;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "dataCriacao", ignore = true)

    // 👇 mapeamento explícito do campo senha
    @Mapping(target = "senha", source = "senha") 
    @Mapping(source = "avatarUrl", target = "avatar")
    User toEntity(RegisterRequest dto);

    PerfilResponse toPerfilResponse(User user);
}

