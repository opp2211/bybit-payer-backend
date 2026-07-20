package ru.maltsev.bybitpayerbackend.security.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.user.repository.UserRepository;
import ru.maltsev.bybitpayerbackend.user.service.UserNormalizer;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final UserNormalizer normalizer;

    public UserEntity currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw BusinessException.badRequest("Authentication is required");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof SecurityUser securityUser) {
            return userRepository.findById(securityUser.id())
                    .orElseThrow(() -> new EntityNotFoundException("Authenticated user not found"));
        }
        if (principal instanceof UserDetails userDetails) {
            return findByLogin(userDetails.getUsername());
        }
        if (principal instanceof String value) {
            return findByLogin(value);
        }
        throw BusinessException.badRequest("Unsupported authentication principal");
    }

    private UserEntity findByLogin(String login) {
        String normalized = normalizer.normalizeLookup(login);
        return userRepository.findByUsernameNormalized(normalized)
                .or(() -> userRepository.findByEmailNormalized(normalized))
                .orElseThrow(() -> new EntityNotFoundException("Authenticated user not found"));
    }
}
