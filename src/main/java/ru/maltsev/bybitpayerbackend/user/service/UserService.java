package ru.maltsev.bybitpayerbackend.user.service;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.common.service.PublicIdGenerator;
import ru.maltsev.bybitpayerbackend.user.dto.RegisterRequest;
import ru.maltsev.bybitpayerbackend.user.dto.UserResponse;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.user.model.UserRole;
import ru.maltsev.bybitpayerbackend.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserNormalizer normalizer;
    private final PublicIdGenerator publicIdGenerator;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim();
        String usernameNormalized = normalizer.normalizeUsername(username);
        String emailNormalized = normalizer.normalizeEmail(email);

        if (userRepository.existsByUsernameNormalized(usernameNormalized)) {
            throw BusinessException.conflict("Username is already registered");
        }
        if (userRepository.existsByEmailNormalized(emailNormalized)) {
            throw BusinessException.conflict("Email is already registered");
        }

        Instant now = Instant.now(clock);
        UserEntity user = new UserEntity();
        user.setPublicId(publicIdGenerator.generate(userRepository::existsByPublicId));
        user.setUsername(username);
        user.setUsernameNormalized(usernameNormalized);
        user.setEmail(email);
        user.setEmailNormalized(emailNormalized);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setEmailVerified(false);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return toResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public UserEntity getRequired(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + id));
    }

    public UserResponse toResponse(UserEntity user) {
        return new UserResponse(
                user.getPublicId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole().name(),
                user.isEmailVerified()
        );
    }
}
