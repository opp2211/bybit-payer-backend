package ru.maltsev.bybitpayerbackend.receipt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffMailReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptData;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.receipt.entity.IgnoredEmailReceiptEntity;
import ru.maltsev.bybitpayerbackend.receipt.model.ReceiptVerificationStatus;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.receipt.repository.IgnoredEmailReceiptRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

class ReceiptVerificationWorkerTests {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    @Test
    void foreignReceiptDoesNotBlockNextWithdrawal() {
        Fixture fixture = new Fixture();
        WithdrawalRequestEntity first = withdrawal(
                1L,
                "order-1",
                "+7 (900) 111-11-11",
                new BigDecimal("1000")
        );
        WithdrawalRequestEntity second = withdrawal(
                2L,
                "order-2",
                "+7 (900) 222-22-22",
                new BigDecimal("2000")
        );
        when(fixture.withdrawalRepository.findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.PAYMENT_VERIFICATION))
                .thenReturn(List.of(first, second));

        TinkoffMailReceiptValidationResult secondReceiptSeenByFirst = mailResult(
                false,
                false,
                "second-receipt",
                second.getRecipientPhone(),
                new BigDecimal("2000"),
                List.of("Телефон не совпал")
        );
        TinkoffMailReceiptValidationResult invalidFirstReceipt = mailResult(
                false,
                true,
                "first-receipt",
                first.getRecipientPhone(),
                new BigDecimal("900"),
                List.of("В чеке не найдена ожидаемая сумма: 1000")
        );
        TinkoffMailReceiptValidationResult validSecondReceipt = mailResult(
                true,
                true,
                "second-receipt",
                second.getRecipientPhone(),
                new BigDecimal("2000"),
                List.of()
        );

        when(fixture.mailService.findForWithdrawal(
                argThat(request -> request != null && first.getRecipientPhone().equals(request.phone())),
                any()
        )).thenReturn(List.of(secondReceiptSeenByFirst, invalidFirstReceipt));
        when(fixture.mailService.findForWithdrawal(
                argThat(request -> request != null && second.getRecipientPhone().equals(request.phone())),
                any()
        )).thenReturn(List.of(validSecondReceipt));

        fixture.worker.verifyPendingPayments();

        assertThat(first.getStatus()).isEqualTo(WithdrawalStatus.ERROR);
        assertThat(first.isAttentionRequired()).isTrue();
        assertThat(first.getLastWarning()).contains("данные не совпали", "сумма");
        assertThat(second.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);

        ArgumentCaptor<IgnoredEmailReceiptEntity> ignoredCaptor =
                ArgumentCaptor.forClass(IgnoredEmailReceiptEntity.class);
        verify(fixture.ignoredReceiptRepository).save(ignoredCaptor.capture());
        assertThat(ignoredCaptor.getValue().getWithdrawalRequest()).isSameAs(first);
        assertThat(ignoredCaptor.getValue().getReceiptKey()).isEqualTo("second-receipt");

        assertThat(fixture.savedChecks)
                .extracting(check -> check.getWithdrawalRequest().getId())
                .containsExactly(1L, 2L);
        verify(fixture.bybitGateway).releaseOrder("order-2");
        verify(fixture.bybitGateway, never()).releaseOrder("order-1");
    }

    @Test
    void retriesReleaseUsingPreviouslyVerifiedReceipt() {
        Fixture fixture = new Fixture();
        WithdrawalRequestEntity withdrawal = withdrawal(
                1L,
                "order-1",
                "+7 (900) 111-11-11",
                new BigDecimal("1000")
        );
        EmailReceiptCheckEntity verifiedCheck = new EmailReceiptCheckEntity();
        verifiedCheck.setId(10L);
        verifiedCheck.setWithdrawalRequest(withdrawal);
        verifiedCheck.setVerificationStatus(ReceiptVerificationStatus.VERIFIED);

        when(fixture.withdrawalRepository.findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.PAYMENT_VERIFICATION))
                .thenReturn(List.of(withdrawal));
        when(fixture.receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        withdrawal.getId(),
                        withdrawal.getBybitOrderId(),
                        ReceiptVerificationStatus.VERIFIED
                ))
                .thenReturn(Optional.of(verifiedCheck));

        fixture.worker.verifyPendingPayments();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        verify(fixture.bybitGateway).releaseOrder("order-1");
        verify(fixture.mailService, never()).findForWithdrawal(any(), any());
    }

    @Test
    void manualPayerBankTypeIsNotAutoReleased() {
        Fixture fixture = new Fixture();
        WithdrawalRequestEntity withdrawal = withdrawal(
                1L,
                "order-1",
                "+7 (900) 111-11-11",
                new BigDecimal("1000")
        );
        withdrawal.setPayerBankType(PayerBankType.ANY_BANK);
        when(fixture.withdrawalRepository.findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.PAYMENT_VERIFICATION))
                .thenReturn(List.of(withdrawal));

        fixture.worker.verifyPendingPayments();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PAYMENT_VERIFICATION);
        verify(fixture.mailService, never()).findForWithdrawal(any(), any());
        verify(fixture.bybitGateway, never()).releaseOrder("order-1");
    }

    private WithdrawalRequestEntity withdrawal(Long id, String orderId, String phone, BigDecimal amount) {
        BankEntity bank = new BankEntity();
        bank.setCode("SBERBANK");
        bank.setTitle("Сбербанк");

        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(id);
        withdrawal.setBybitOrderId(orderId);
        withdrawal.setRecipientPhone(phone);
        withdrawal.setRecipientName("Иван Петров");
        withdrawal.setRecipientBank(bank);
        withdrawal.setAmountRub(amount);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_VERIFICATION);
        withdrawal.setPayerBankType(PayerBankType.TBANK_AUTO);
        return withdrawal;
    }

    private TinkoffMailReceiptValidationResult mailResult(
            boolean valid,
            boolean phoneMatches,
            String receiptKey,
            String phone,
            BigDecimal amount,
            List<String> errors
    ) {
        return new TinkoffMailReceiptValidationResult(
                valid,
                phoneMatches,
                receiptKey,
                "<" + receiptKey + "@example.com>",
                "Документ по операции",
                "noreply@tinkoff.ru",
                NOW,
                "receipt.pdf",
                new byte[]{1, 2, 3},
                new TinkoffReceiptData(
                        amount,
                        "Успешно",
                        "Иван Петров",
                        phone,
                        "Сбербанк"
                ),
                errors
        );
    }

    private static class Fixture {

        private final ReceiptMailProperties mailProperties = new ReceiptMailProperties();
        private final TinkoffReceiptMailService mailService = mock(TinkoffReceiptMailService.class);
        private final EmailReceiptCheckRepository receiptCheckRepository =
                mock(EmailReceiptCheckRepository.class);
        private final IgnoredEmailReceiptRepository ignoredReceiptRepository =
                mock(IgnoredEmailReceiptRepository.class);
        private final WithdrawalRequestRepository withdrawalRepository =
                mock(WithdrawalRequestRepository.class);
        private final BybitOrderBindingRepository bindingRepository =
                mock(BybitOrderBindingRepository.class);
        private final BybitGateway bybitGateway = mock(BybitGateway.class);
        private final WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        private final List<EmailReceiptCheckEntity> savedChecks = new ArrayList<>();
        private final ReceiptVerificationWorker worker;

        private Fixture() {
            mailProperties.setEnabled(true);
            when(ignoredReceiptRepository.findReceiptKeysByWithdrawalRequestId(any()))
                    .thenReturn(Set.of());
            when(ignoredReceiptRepository.existsByWithdrawalRequest_IdAndReceiptKey(any(), any()))
                    .thenReturn(false);
            when(receiptCheckRepository
                    .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                            any(),
                            any(),
                            any()
                    ))
                    .thenReturn(Optional.empty());
            AtomicLong checkId = new AtomicLong(1);
            when(receiptCheckRepository.save(any())).thenAnswer(invocation -> {
                EmailReceiptCheckEntity check = invocation.getArgument(0);
                check.setId(checkId.getAndIncrement());
                savedChecks.add(check);
                return check;
            });
            when(withdrawalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(bindingRepository.findByWithdrawalRequest_IdAndStatus(any(), any()))
                    .thenReturn(Optional.empty());

            worker = new ReceiptVerificationWorker(
                    mailProperties,
                    mailService,
                    receiptCheckRepository,
                    ignoredReceiptRepository,
                    withdrawalRepository,
                    bindingRepository,
                    bybitGateway,
                    eventService,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );
        }
    }
}
