package ru.maltsev.bybitpayerbackend.security.config;

import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import ru.maltsev.bybitpayerbackend.security.filter.LoginRateLimitFilter;
import ru.maltsev.bybitpayerbackend.security.service.LoginAttemptService;

@Configuration
public class SecurityConfig {

    public static final String REMEMBER_ME_COOKIE = "FLOWPAY_REMEMBER_ME";

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AuthProperties properties) {
        UserDetails operator = User.withUsername(properties.username())
                .password(properties.passwordHash())
                .roles("OPERATOR")
                .build();
        return new InMemoryUserDetailsManager(operator);
    }

    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    @Bean
    PersistentTokenBasedRememberMeServices rememberMeServices(
            AuthProperties properties,
            UserDetailsService userDetailsService,
            PersistentTokenRepository tokenRepository
    ) {
        PersistentTokenBasedRememberMeServices services = new PersistentTokenBasedRememberMeServices(
                properties.rememberMeKey(),
                userDetailsService,
                tokenRepository
        );
        services.setAlwaysRemember(true);
        services.setTokenValiditySeconds(toValiditySeconds(properties.rememberMeValidity()));
        services.setCookieName(REMEMBER_ME_COOKIE);
        services.setCookieCustomizer(cookie -> {
            cookie.setPath("/");
            cookie.setSecure(properties.rememberMeSecure());
            cookie.setAttribute("SameSite", "Strict");
        });
        return services;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PersistentTokenBasedRememberMeServices rememberMeServices,
            LoginAttemptService loginAttemptService
    ) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/auth/csrf",
                                "/api/auth/login",
                                "/actuator/health/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            loginAttemptService.clear(request);
                            response.setStatus(HttpStatus.NO_CONTENT.value());
                        })
                        .failureHandler((request, response, exception) -> {
                            loginAttemptService.registerFailure(request);
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/json");
                            response.getWriter().write("{\"message\":\"Invalid username or password\"}");
                        })
                        .permitAll()
                )
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID", REMEMBER_ME_COOKIE)
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())
                        )
                )
                .addFilterBefore(
                        new LoginRateLimitFilter(loginAttemptService),
                        UsernamePasswordAuthenticationFilter.class
                );
        return http.build();
    }

    private int toValiditySeconds(Duration validity) {
        long seconds = validity.toSeconds();
        if (seconds < 1 || seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("auth.remember-me-validity must fit into 1..2147483647 seconds");
        }
        return (int) seconds;
    }
}
