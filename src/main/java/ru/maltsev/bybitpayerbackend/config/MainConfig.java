package ru.maltsev.bybitpayerbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class MainConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
