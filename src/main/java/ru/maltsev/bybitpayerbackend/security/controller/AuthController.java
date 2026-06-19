package ru.maltsev.bybitpayerbackend.security.controller;

import java.security.Principal;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.security.dto.AuthenticatedUserResponse;
import ru.maltsev.bybitpayerbackend.security.dto.CsrfTokenResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse me(Principal principal) {
        return new AuthenticatedUserResponse(principal.getName());
    }
}
