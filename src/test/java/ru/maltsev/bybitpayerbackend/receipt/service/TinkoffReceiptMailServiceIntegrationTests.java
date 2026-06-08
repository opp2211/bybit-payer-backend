package ru.maltsev.bybitpayerbackend.receipt.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffMailReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;

@SpringBootTest
@EnabledIfSystemProperty(named = "receipt.mail.integration-test", matches = "true")
class TinkoffReceiptMailServiceIntegrationTests {

    @Autowired
    private TinkoffReceiptMailService mailService;

    @Autowired
    private ReceiptMailProperties mailProperties;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void configureRealMailTest(DynamicPropertyRegistry registry) {
        registry.add("receipt.mail.enabled", () -> "true");
        registry.add("receipt.mail.only-unread", () -> "false");
        registry.add("receipt.mail.max-messages", () -> "100");
    }

    @Test
    void findsValidReceiptInRealGmailInbox() {
        assertThat(mailProperties.getUsername()).isNotBlank();
        assertThat(mailProperties.getPassword()).isNotBlank();

        TinkoffReceiptVerificationRequest request = new TinkoffReceiptVerificationRequest(
                new BigDecimal(1775),
                "Иван К.",
                "+7 (904) 548-81-56",
                "Озон Банк (Ozon)"
        );

        List<TinkoffMailReceiptValidationResult> results = mailService.findAndValidate(request);

        assertThat(results)
                .as("Gmail should contain messages from %s with receipt PDFs", mailProperties.getSender())
                .isNotEmpty();
        assertThat(results)
                .as("At least one receipt should match expected test data")
                .anySatisfy(result -> {
                    assertThat(result.valid()).isTrue();
                    assertThat(result.receipt().amount()).isEqualByComparingTo(request.amount());
                    assertThat(result.receipt().recipient()).isEqualTo(request.recipient());
                    assertThat(result.receipt().phone()).isEqualTo(request.phone());
                    assertThat(result.receipt().bank()).isEqualTo(request.bank());
                });
    }

    private String property(String environmentVariable, String systemProperty) {
        String value = environment.getProperty(systemProperty);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return environment.getProperty(environmentVariable, "");
    }

    private String required(String environmentVariable, String systemProperty) {
        String value = property(environmentVariable, systemProperty);
        assertThat(value)
                .as("Set %s or -D%s for real mail integration test", environmentVariable, systemProperty)
                .isNotBlank();
        return value;
    }
}
