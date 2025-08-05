package br.ars.user_service.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import br.ars.user_service.models.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}
