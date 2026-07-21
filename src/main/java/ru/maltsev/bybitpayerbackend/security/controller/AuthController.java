package ru.maltsev.bybitpayerbackend.security.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.security.dto.AuthenticatedUserResponse;
import ru.maltsev.bybitpayerbackend.security.dto.CsrfTokenResponse;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.dto.RegisterRequest;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.user.service.UserService;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final CurrentUserService currentUserService;
    private final UserService userService;

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse me() {
        return toAuthenticatedUser(currentUserService.currentUser());
    }

    @PostMapping("/register")
    public AuthenticatedUserResponse register(@Valid @RequestBody RegisterRequest request) {
        return toAuthenticatedUserResponse(userService.register(request));
    }

    private AuthenticatedUserResponse toAuthenticatedUser(UserEntity user) {
        return new AuthenticatedUserResponse(
                user.getPublicId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole().name(),
                user.isEmailVerified()
        );
    }

    private AuthenticatedUserResponse toAuthenticatedUserResponse(ru.maltsev.bybitpayerbackend.user.dto.UserResponse user) {
        return new AuthenticatedUserResponse(
                user.publicId(),
                user.username(),
                user.email(),
                user.role(),
                user.emailVerified()
        );
    }
}
