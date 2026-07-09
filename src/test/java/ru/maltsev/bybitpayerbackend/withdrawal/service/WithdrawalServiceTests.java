package ru.maltsev.bybitpayerbackend.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bank.service.BankService;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalEventRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;

class WithdrawalServiceTests {

    @Test
    void releasesOrderManuallyAndCompletesWithdrawal() {
        Instant now = Instant.parse("2026-06-09T12:00:00Z");
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitOrderBindingRepository bindingRepository = mock(BybitOrderBindingRepository.class);
        BybitGateway gateway = mock(BybitGateway.class);
        WithdrawalMapper mapper = mock(WithdrawalMapper.class);
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        BybitOrderBindingEntity binding = new BybitOrderBindingEntity();
        WithdrawalResponse response = mock(WithdrawalResponse.class);

        withdrawal.setId(1L);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_VERIFICATION);
        withdrawal.setBybitOrderId("order-1");
        binding.setStatus(OrderBindingStatus.ACTIVE);
        binding.setWithdrawalRequest(withdrawal);

        when(withdrawalRepository.findById(1L)).thenReturn(Optional.of(withdrawal));
        when(bindingRepository.findByWithdrawalRequest_IdAndStatus(1L, OrderBindingStatus.ACTIVE))
                .thenReturn(Optional.of(binding));
        when(withdrawalRepository.save(withdrawal)).thenReturn(withdrawal);
        when(mapper.toResponse(withdrawal)).thenReturn(response);

        WithdrawalService service = new WithdrawalService(
                withdrawalRepository,
                mock(WithdrawalEventRepository.class),
                mock(BybitChatService.class),
                mock(EmailReceiptCheckRepository.class),
                bindingRepository,
                mock(WithdrawalInputNormalizer.class),
                mock(WithdrawalEventService.class),
                mock(AdvertisementManager.class),
                gateway,
                mock(BankService.class),
                mapper,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        assertThat(service.release(1L)).isSameAs(response);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(withdrawal.getCompletedAt()).isEqualTo(now);
        assertThat(binding.getStatus()).isEqualTo(OrderBindingStatus.RELEASED);
        verify(gateway).releaseOrder("order-1");
    }
}
