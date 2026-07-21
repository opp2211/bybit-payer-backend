package ru.maltsev.bybitpayerbackend.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    boolean existsByPublicId(String publicId);

    boolean existsByUsernameNormalized(String usernameNormalized);

    boolean existsByEmailNormalized(String emailNormalized);

    Optional<UserEntity> findByPublicId(String publicId);

    Optional<UserEntity> findByUsernameNormalized(String usernameNormalized);

    Optional<UserEntity> findByEmailNormalized(String emailNormalized);
}
