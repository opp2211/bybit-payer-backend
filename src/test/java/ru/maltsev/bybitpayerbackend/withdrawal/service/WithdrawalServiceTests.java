package ru.maltsev.bybitpayerbackend.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ru.maltsev.bybitpayerbackend.ai.service.AiChatAgentService;
import ru.maltsev.bybitpayerbackend.bank.service.BankService;
import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitOrderWatcher;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.service.PublicIdGenerator;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalEventRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;

class WithdrawalServiceTests {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    @Test
    void releasesOrderManuallyAndCompletesWithdrawal() {
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
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(service.release(1L)).isSameAs(response);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(withdrawal.getCompletedAt()).isEqualTo(NOW);
        assertThat(binding.getStatus()).isEqualTo(OrderBindingStatus.RELEASED);
        verify(gateway).releaseOrder("order-1");
    }

    @Test
    void rebuildsPublicationAndChecksBybitBeforeCancellingInWorkWithdrawal() {
        CancellationFixture fixture = new CancellationFixture();
        doAnswer(invocation -> {
            assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.CANCELLATION_PENDING);
            return null;
        }).when(fixture.advertisementManager).rebuildPublication(fixture.workspace);

        WithdrawalResponse result = fixture.service.cancel("workspace-1", "withdrawal-1");

        assertThat(result).isSameAs(fixture.response);
        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.CANCELLED);
        assertThat(fixture.withdrawal.getCancelledAt()).isEqualTo(NOW);
        InOrder safetySequence = inOrder(fixture.advertisementManager, fixture.bybitOrderWatcher);
        safetySequence.verify(fixture.advertisementManager).rebuildPublication(fixture.workspace);
        safetySequence.verify(fixture.bybitOrderWatcher).pollActiveOrders(fixture.workspace);
    }

    @Test
    void blocksCancellationWhenLateBybitOrderGetsBoundDuringSafetyCheck() {
        CancellationFixture fixture = new CancellationFixture();
        doAnswer(invocation -> {
            fixture.withdrawal.setStatus(WithdrawalStatus.PAYMENT_IN_PROGRESS);
            fixture.withdrawal.setBybitOrderId("late-order");
            return null;
        }).when(fixture.bybitOrderWatcher).pollActiveOrders(fixture.workspace);

        assertThatThrownBy(() -> fixture.service.cancel("workspace-1", "withdrawal-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Bybit");

        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        assertThat(fixture.withdrawal.getBybitOrderId()).isEqualTo("late-order");
        verify(fixture.mapper, never()).toResponse(fixture.withdrawal);
    }

    @Test
    void doesNotBlockCancellationBecauseOfUnrelatedOrderWithSameAmount() {
        CancellationFixture fixture = new CancellationFixture();

        fixture.service.cancel("workspace-1", "withdrawal-1");

        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.CANCELLED);
        verify(fixture.bybitGateway, never()).fetchActiveOrders();
    }

    private static class CancellationFixture {

        private final WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        private final WithdrawalMapper mapper = mock(WithdrawalMapper.class);
        private final AdvertisementManager advertisementManager = mock(AdvertisementManager.class);
        private final BybitGateway bybitGateway = mock(BybitGateway.class);
        private final BybitOrderWatcher bybitOrderWatcher = mock(BybitOrderWatcher.class);
        private final CurrentUserService currentUserService = mock(CurrentUserService.class);
        private final WorkspaceAccessService workspaceAccessService = mock(WorkspaceAccessService.class);
        private final WorkspaceEntity workspace = new WorkspaceEntity();
        private final UserEntity user = new UserEntity();
        private final WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        private final WithdrawalResponse response = mock(WithdrawalResponse.class);
        private final WithdrawalService service;

        private CancellationFixture() {
            workspace.setId(1L);
            workspace.setPublicId("workspace-1");
            withdrawal.setId(1L);
            withdrawal.setPublicId("withdrawal-1");
            withdrawal.setWorkspace(workspace);
            withdrawal.setStatus(WithdrawalStatus.IN_WORK);

            when(currentUserService.currentUser()).thenReturn(user);
            when(workspaceAccessService.getAccessibleWorkspace("workspace-1", user)).thenReturn(workspace);
            when(withdrawalRepository.findForUpdateByWorkspaceAndPublicId(workspace, "withdrawal-1"))
                    .thenReturn(Optional.of(withdrawal));
            when(withdrawalRepository.save(withdrawal)).thenReturn(withdrawal);
            when(mapper.toResponse(withdrawal)).thenReturn(response);

            BusinessProperties businessProperties = new BusinessProperties();
            businessProperties.setWithdrawalCancellationGracePeriod(Duration.ZERO);
            service = new WithdrawalService(
                    withdrawalRepository,
                    mock(WithdrawalEventRepository.class),
                    mock(BybitChatService.class),
                    mock(EmailReceiptCheckRepository.class),
                    mock(BybitOrderBindingRepository.class),
                    mock(WithdrawalInputNormalizer.class),
                    mock(WithdrawalEventService.class),
                    advertisementManager,
                    bybitGateway,
                    new BybitCredentialsContext(),
                    mock(BankService.class),
                    mapper,
                    currentUserService,
                    workspaceAccessService,
                    mock(WorkspaceSecretService.class),
                    mock(PublicIdGenerator.class),
                    mock(AuditService.class),
                    mock(AiChatAgentService.class),
                    bybitOrderWatcher,
                    businessProperties,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    null
            );
        }
    }
}
