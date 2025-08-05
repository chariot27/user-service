package br.ars.user_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import br.ars.user_service.dto.RegisterRequest;
import br.ars.user_service.models.User;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "dataCriacao", ignore = true)
    User toEntity(RegisterRequest dto);
}
