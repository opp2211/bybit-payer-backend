package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitChatMessage;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

class BybitChatServiceTests {

    @Test
    void sendsOperatorMessageDirectlyToBybit() {
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway bybitGateway = mock(BybitGateway.class);
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(7L);
        withdrawal.setBybitOrderId("order-7");

        when(withdrawalRepository.findById(7L)).thenReturn(Optional.of(withdrawal));

        BybitChatService service = service(withdrawalRepository, eventService, bybitGateway);
        service.sendMessage(7L, "  hello  ");

        verify(bybitGateway).sendChatMessage(eq("order-7"), anyString(), eq("hello"));
        verify(eventService).add(withdrawal, WithdrawalEventType.CHAT_MESSAGE_SENT, "Chat message sent by operator");
    }

    @Test
    void returnsRemoteChatHistoryOnly() {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(7L);
        withdrawal.setBybitOrderId("order-7");
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway bybitGateway = mock(BybitGateway.class);
        Instant first = Instant.parse("2026-06-09T12:00:00Z");
        Instant second = Instant.parse("2026-06-09T12:01:00Z");

        when(bybitGateway.fetchChatMessages("order-7")).thenReturn(List.of(
                new BybitChatMessage(
                        "remote-2",
                        "sent",
                        "seller-1",
                        1,
                        second,
                        "str",
                        "order-7",
                        "uuid-2",
                        "Seller",
                        "merchant"
                ),
                new BybitChatMessage(
                        "remote-1",
                        "received",
                        "buyer-1",
                        1,
                        first,
                        "str",
                        "order-7",
                        "",
                        "Buyer",
                        "user"
                )
        ));

        BybitChatService service = service(withdrawalRepository, eventService, bybitGateway);
        List<ChatMessageLogResponse> messages = service.getMessages(withdrawal);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).id()).isEqualTo("bybit:remote-1");
        assertThat(messages.get(0).messageIndex()).isNull();
        assertThat(messages.get(0).direction()).isEqualTo("INCOMING");
        assertThat(messages.get(0).authorName()).isEqualTo("Buyer");
        assertThat(messages.get(1).id()).isEqualTo("bybit:remote-2");
        assertThat(messages.get(1).messageIndex()).isNull();
        assertThat(messages.get(1).direction()).isEqualTo("OUTGOING");
        assertThat(messages.get(1).authorName()).isEqualTo("Вы");
    }

    @Test
    void skipsReceiptEmailWhenPayerBankTypeRequiresManualRelease() {
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway bybitGateway = mock(BybitGateway.class);
        BankEntity bank = new BankEntity();
        bank.setTitle("Сбербанк");
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(7L);
        withdrawal.setBybitOrderId("order-7");
        withdrawal.setRecipientPhone("+79001112233");
        withdrawal.setRecipientBank(bank);
        withdrawal.setRecipientName("Иван Петров");
        withdrawal.setPayerBankType(PayerBankType.SBERBANK);

        BusinessProperties businessProperties = new BusinessProperties();
        businessProperties.setChatMessageDelay(Duration.ZERO);
        businessProperties.setReceiptEmailToSendInChat("receipts@example.com");
        BybitChatService service = new BybitChatService(
                withdrawalRepository,
                eventService,
                bybitGateway,
                businessProperties,
                Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC)
        );

        service.sendRequisites(withdrawal);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(bybitGateway, times(3)).sendChatMessage(eq("order-7"), anyString(), messageCaptor.capture());
        assertThat(messageCaptor.getAllValues())
                .containsExactly("Привет", "+79001112233", "Сбербанк, Иван Петров");
    }

    private BybitChatService service(
            WithdrawalRequestRepository withdrawalRepository,
            WithdrawalEventService eventService,
            BybitGateway bybitGateway
    ) {
        BusinessProperties businessProperties = new BusinessProperties();
        businessProperties.setChatMessageDelay(Duration.ZERO);
        return new BybitChatService(
                withdrawalRepository,
                eventService,
                bybitGateway,
                businessProperties,
                Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC)
        );
    }
}
