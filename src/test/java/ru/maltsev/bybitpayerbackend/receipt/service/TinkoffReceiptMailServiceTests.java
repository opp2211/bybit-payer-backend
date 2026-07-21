package ru.maltsev.bybitpayerbackend.receipt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.SearchTerm;
import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffMailReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptData;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;

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

    @Test
    void readsImapContentWithoutMarkingMessagesSeen() {
        TinkoffReceiptMailService service = new TinkoffReceiptMailService(mailProperties(), null);

        assertThat(service.mailSessionProperties())
                .containsEntry("mail.imaps.peek", "true");
    }

    @Test
    void leavesForeignReceiptUnreadAndClaimsReceiptWithMatchingPhone() throws Exception {
        TinkoffReceiptValidator validator = mock(TinkoffReceiptValidator.class);
        TinkoffReceiptMailService service = new TinkoffReceiptMailService(mailProperties(), validator);
        Folder folder = mock(Folder.class);
        Message foreignMessage = pdfMessage(
                "<foreign@example.com>",
                Instant.parse("2026-06-08T12:01:00Z")
        );
        Message matchingMessage = pdfMessage(
                "<matching@example.com>",
                Instant.parse("2026-06-08T12:00:00Z")
        );
        when(folder.search(any(SearchTerm.class))).thenReturn(new Message[]{foreignMessage, matchingMessage});

        TinkoffReceiptVerificationRequest expected = expectedRequest("+7 (900) 111-22-33");
        when(validator.validatePdf(any(byte[].class), eq(expected)))
                .thenReturn(validationResult(false, "+7 (999) 000-00-00"))
                .thenReturn(validationResult(true, expected.phone()));
        when(validator.matchesReceiptKey(any(), eq(expected)))
                .thenReturn(false)
                .thenReturn(true);

        List<TinkoffMailReceiptValidationResult> results = service.validateFolderMessages(
                folder,
                expected,
                Set.of(),
                true
        );

        assertThat(results).hasSize(2);
        assertThat(results.get(0).recipientPhoneMatches()).isFalse();
        assertThat(results.get(1).recipientPhoneMatches()).isTrue();
        verify(foreignMessage).setFlag(Flags.Flag.SEEN, false);
        verify(matchingMessage).setFlag(Flags.Flag.SEEN, true);
    }

    @Test
    void skipsPreviouslyIgnoredReceiptBeforeLoadingPdf() throws Exception {
        TinkoffReceiptValidator validator = mock(TinkoffReceiptValidator.class);
        TinkoffReceiptMailService service = new TinkoffReceiptMailService(mailProperties(), validator);
        Folder folder = mock(Folder.class);
        Message message = pdfMessage(
                "<ignored@example.com>",
                Instant.parse("2026-06-08T12:00:00Z")
        );
        when(folder.search(any(SearchTerm.class))).thenReturn(new Message[]{message});

        TinkoffReceiptVerificationRequest expected = expectedRequest("+7 (900) 111-22-33");
        when(validator.validatePdf(any(byte[].class), eq(expected)))
                .thenReturn(validationResult(false, "+7 (999) 000-00-00"));
        when(validator.matchesPhone("+7 (999) 000-00-00", expected.phone())).thenReturn(false);

        List<TinkoffMailReceiptValidationResult> firstScan = service.validateFolderMessages(
                folder,
                expected,
                Set.of(),
                true
        );
        clearInvocations(message, validator);

        List<TinkoffMailReceiptValidationResult> secondScan = service.validateFolderMessages(
                folder,
                expected,
                Set.of(firstScan.getFirst().receiptKey()),
                true
        );

        assertThat(secondScan).isEmpty();
        verify(message, never()).getInputStream();
        verify(validator, never()).validatePdf(any(byte[].class), eq(expected));
    }

    private Message pdfMessage(String messageId, Instant receivedAt) throws Exception {
        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(new InternetAddress[]{
                new InternetAddress("T-Bank <noreply@tinkoff.ru>")
        });
        when(message.getSubject()).thenReturn("Документ по операции");
        when(message.getReceivedDate()).thenReturn(Date.from(receivedAt));
        when(message.getHeader("Message-ID")).thenReturn(new String[]{messageId});
        when(message.isMimeType("multipart/*")).thenReturn(false);
        when(message.isMimeType("application/pdf")).thenReturn(true);
        when(message.getFileName()).thenReturn("receipt.pdf");
        when(message.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        return message;
    }

    private TinkoffReceiptVerificationRequest expectedRequest(String phone) {
        return new TinkoffReceiptVerificationRequest(
                new BigDecimal("1000"),
                "Иван Петров",
                phone,
                "Сбербанк"
        );
    }

    private TinkoffReceiptValidationResult validationResult(boolean valid, String phone) {
        return new TinkoffReceiptValidationResult(
                valid,
                new TinkoffReceiptData(
                        new BigDecimal("1000"),
                        "Успешно",
                        "Иван Петров",
                        phone,
                        "Сбербанк",
                        null
                ),
                valid ? List.of() : List.of("Телефон не совпал")
        );
    }

    private ReceiptMailProperties mailProperties() {
        ReceiptMailProperties properties = new ReceiptMailProperties();
        properties.setSender("noreply@tinkoff.ru");
        return properties;
    }
}
