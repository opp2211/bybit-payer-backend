package ru.maltsev.bybitpayerbackend.ai.config;

import java.net.URI;
import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey;
    private String model = "gpt-5-nano";
    private URI responsesUrl = URI.create("https://api.openai.com/v1/responses");
    private Duration timeout = Duration.ofSeconds(15);
}
