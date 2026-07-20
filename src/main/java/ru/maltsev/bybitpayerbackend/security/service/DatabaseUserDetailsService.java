package ru.maltsev.bybitpayerbackend.security.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.user.repository.UserRepository;
import ru.maltsev.bybitpayerbackend.user.service.UserNormalizer;

@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserNormalizer normalizer;

    @Override
    public UserDetails loadUserByUsername(String usernameOrEmail) {
        String normalized = normalizer.normalizeLookup(usernameOrEmail);
        return userRepository.findByUsernameNormalized(normalized)
                .or(() -> userRepository.findByEmailNormalized(normalized))
                .map(SecurityUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
