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
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageContentType;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageSenderType;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitChatMessage;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

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
        assertThat(messages.get(0).senderType()).isEqualTo(ChatMessageSenderType.COUNTERPARTY);
        assertThat(messages.get(0).authorName()).isEqualTo("Buyer");
        assertThat(messages.get(0).content().type()).isEqualTo(ChatMessageContentType.TEXT);
        assertThat(messages.get(0).content().text()).isEqualTo("received");
        assertThat(messages.get(1).id()).isEqualTo("bybit:remote-2");
        assertThat(messages.get(1).senderType()).isEqualTo(ChatMessageSenderType.COUNTERPARTY);
        assertThat(messages.get(1).authorName()).isEqualTo("Seller");
    }

    @Test
    void usesCacheForRepeatedChatReadsWithinTtl() {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(7L);
        withdrawal.setBybitOrderId("order-7");
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway bybitGateway = mock(BybitGateway.class);
        when(bybitGateway.fetchChatMessages("order-7")).thenReturn(List.of(new BybitChatMessage(
                "remote-1",
                "received",
                "buyer-1",
                1,
                Instant.parse("2026-06-09T12:00:00Z"),
                "str",
                "order-7",
                "uuid-1",
                "Buyer",
                "user"
        )));

        BybitChatService service = service(withdrawalRepository, eventService, bybitGateway);

        assertThat(service.getMessages(withdrawal)).hasSize(1);
        assertThat(service.getMessages(withdrawal)).hasSize(1);

        verify(bybitGateway, times(1)).fetchChatMessages("order-7");
    }

    @Test
    void formatsSystemAndAttachmentMessagesAndHidesOrderCard() {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(7L);
        withdrawal.setBybitOrderId("order-7");
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway bybitGateway = mock(BybitGateway.class);
        when(bybitGateway.fetchChatMessages("order-7")).thenReturn(List.of(
                new BybitChatMessage(
                        "card",
                        "{\"orderId\":\"order-7\"}",
                        "0",
                        11,
                        Instant.parse("2026-06-09T11:59:00Z"),
                        "SYS_ORDER_CARD",
                        "order-7",
                        "card-uuid",
                        "",
                        "sys"
                ),
                new BybitChatMessage(
                        "alarm",
                        "Fraud cases. <a href=\"https://example.com\">Read More</a>",
                        "seller-1",
                        103,
                        Instant.parse("2026-06-09T12:00:00Z"),
                        "str",
                        "order-7",
                        "alarm-uuid",
                        "ExPrime",
                        "alarm",
                        "seller-account",
                        1201,
                        ""
                ),
                new BybitChatMessage(
                        "pic",
                        "/fiat/p2p/oss/showObj/file.jpg?token=abc",
                        "buyer-1",
                        2,
                        Instant.parse("2026-06-09T12:01:00Z"),
                        "pic",
                        "order-7",
                        "",
                        "Buyer",
                        "user",
                        "buyer-account",
                        0,
                        "file.jpg"
                )
        ));

        BybitChatService service = service(withdrawalRepository, eventService, bybitGateway);
        List<ChatMessageLogResponse> messages = service.getMessages(withdrawal);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).senderType()).isEqualTo(ChatMessageSenderType.SYSTEM);
        assertThat(messages.get(0).content().text()).isEqualTo("Fraud cases. Read More");
        assertThat(messages.get(1).senderType()).isEqualTo(ChatMessageSenderType.COUNTERPARTY);
        assertThat(messages.get(1).content().type()).isEqualTo(ChatMessageContentType.IMAGE);
        assertThat(messages.get(1).content().url())
                .isEqualTo("https://api2.bybit.com/fiat/p2p/oss/showObj/file.jpg?token=abc");
        assertThat(messages.get(1).content().fileName()).isEqualTo("file.jpg");
    }

    @Test
    void classifiesOwnMessagesAfterSystemTypesWereHandled() {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(7L);
        withdrawal.setBybitOrderId("order-7");
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setBybitUserId("seller-user");
        workspace.setBybitAccountId("seller-account");
        workspace.setBybitNickname("ExPrime");
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway bybitGateway = mock(BybitGateway.class);
        when(bybitGateway.fetchChatMessages("order-7")).thenReturn(List.of(
                new BybitChatMessage(
                        "system",
                        "system text",
                        "seller-user",
                        0,
                        Instant.parse("2026-06-09T12:00:00Z"),
                        "str",
                        "order-7",
                        "system-uuid",
                        "ExPrime",
                        "sys",
                        "seller-account",
                        1011,
                        ""
                ),
                new BybitChatMessage(
                        "own",
                        "sent",
                        "seller-user",
                        1,
                        Instant.parse("2026-06-09T12:01:00Z"),
                        "str",
                        "order-7",
                        "own-uuid",
                        "ExPrime",
                        "user",
                        "seller-account",
                        0,
                        ""
                )
        ));

        BybitChatService service = service(withdrawalRepository, eventService, bybitGateway);
        List<ChatMessageLogResponse> messages = service.getMessages(workspace, withdrawal);

        assertThat(messages).extracting(ChatMessageLogResponse::senderType)
                .containsExactly(ChatMessageSenderType.SYSTEM, ChatMessageSenderType.USER);
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

    @Test
    void sendsCardNumberAndRecipientNameForTbankCardRequisites() {
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway bybitGateway = mock(BybitGateway.class);
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(7L);
        withdrawal.setBybitOrderId("order-7");
        withdrawal.setPayerBankType(PayerBankType.TBANK_AUTO);
        withdrawal.setWithdrawalMethod(WithdrawalMethod.CARD_NUMBER);
        withdrawal.setRecipientCardNumber("2200000000001234");
        withdrawal.setRecipientCardTbank(true);
        withdrawal.setRecipientName("Дмитрий С.");

        BusinessProperties businessProperties = new BusinessProperties();
        businessProperties.setChatMessageDelay(Duration.ZERO);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setReceiptEmail("receipts@example.com");
        BybitChatService service = new BybitChatService(
                withdrawalRepository,
                eventService,
                bybitGateway,
                businessProperties,
                Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC)
        );

        service.sendRequisites(workspace, withdrawal, true);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(bybitGateway, times(4)).sendChatMessage(eq("order-7"), anyString(), messageCaptor.capture());
        assertThat(messageCaptor.getAllValues())
                .containsExactly("Привет", "2200000000001234", "Дмитрий С.", "receipts@example.com");
    }

    @Test
    void sendsAccountNumberAndRecipientNameForSberbankAccountRequisites() {
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway bybitGateway = mock(BybitGateway.class);
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(7L);
        withdrawal.setBybitOrderId("order-7");
        withdrawal.setPayerBankType(PayerBankType.SBERBANK);
        withdrawal.setWithdrawalMethod(WithdrawalMethod.ACCOUNT_NUMBER);
        withdrawal.setRecipientAccountNumber("40817810099910004312");
        withdrawal.setRecipientName("Иван Петров");

        BusinessProperties businessProperties = new BusinessProperties();
        businessProperties.setChatMessageDelay(Duration.ZERO);
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
                .containsExactly("Привет", "40817810099910004312", "Иван Петров");
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
