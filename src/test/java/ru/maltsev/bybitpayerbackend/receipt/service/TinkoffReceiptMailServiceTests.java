package ru.maltsev.bybitpayerbackend.receipt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;

class TinkoffReceiptMailServiceTests {

    @Test
    void allowsExactSenderAddress() throws Exception {
        TinkoffReceiptMailService service = new TinkoffReceiptMailService(mailProperties(), null);
        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(new InternetAddress[]{
                new InternetAddress("T-Bank <noreply@tinkoff.ru>")
        });

        assertThat(service.isAllowedSender(message)).isTrue();
    }

    @Test
    void rejectsAddressThatOnlyContainsExpectedSender() throws Exception {
        TinkoffReceiptMailService service = new TinkoffReceiptMailService(mailProperties(), null);
        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(new InternetAddress[]{
                new InternetAddress("Fake <noreply@tinkoff.ru.fake>")
        });

        assertThat(service.isAllowedSender(message)).isFalse();
    }

    private ReceiptMailProperties mailProperties() {
        ReceiptMailProperties properties = new ReceiptMailProperties();
        properties.setSender("noreply@tinkoff.ru");
        return properties;
    }
}
