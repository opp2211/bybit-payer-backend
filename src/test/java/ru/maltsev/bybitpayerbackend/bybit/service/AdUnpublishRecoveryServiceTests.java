package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitManagedAdStateRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;

class AdUnpublishRecoveryServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    @Test
    void confirmsRemoteUnpublicationBeforeClearingPendingState() {
        Fixture fixture = new Fixture();
        when(fixture.gateway.isManagedAdPublished("ad-123")).thenReturn(true, false);

        fixture.service.retryUnpublishedAds();

        verify(fixture.gateway).unpublishManagedAd("ad-123");
        assertThat(fixture.state.isPublished()).isFalse();
        assertThat(fixture.state.isUnpublishPending()).isFalse();
        assertThat(fixture.state.getLastError()).isNull();
    }

    @Test
    void persistsFailureAndSchedulesAnotherAttempt() {
        Fixture fixture = new Fixture();
        when(fixture.gateway.isManagedAdPublished("ad-123")).thenReturn(true);
        doThrow(new IllegalStateException("Bybit unavailable"))
                .when(fixture.gateway).unpublishManagedAd("ad-123");

        fixture.service.retryUnpublishedAds();

        assertThat(fixture.state.isPublished()).isTrue();
        assertThat(fixture.state.isUnpublishPending()).isTrue();
        assertThat(fixture.state.getUnpublishPendingSince()).isEqualTo(NOW);
        assertThat(fixture.state.getUnpublishNextAttemptAt()).isAfter(NOW);
        assertThat(fixture.state.getLastError()).contains("Bybit unavailable");
    }

    @Test
    void auditingAnAlreadyOfflineAdDoesNotRaiseAnAlert() {
        Fixture fixture = new Fixture();
        fixture.state.setPublished(false);
        when(fixture.gateway.isManagedAdPublished("ad-123")).thenReturn(false);

        fixture.service.retryUnpublishedAds();

        assertThat(fixture.state.isUnpublishPending()).isFalse();
        assertThat(fixture.state.getUnpublishPendingSince()).isNull();
    }

    private static class Fixture {
        private final WorkspaceEntity workspace = new WorkspaceEntity();
        private final BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        private final WithdrawalRequestRepository withdrawals = mock(WithdrawalRequestRepository.class);
        private final BybitManagedAdStateRepository states = mock(BybitManagedAdStateRepository.class);
        private final AdvertisementManager manager = mock(AdvertisementManager.class);
        private final BybitGateway gateway = mock(BybitGateway.class);
        private final AdUnpublishRecoveryService service;

        private Fixture() {
            workspace.setId(1L);
            workspace.setPublicId("workspace-1");
            state.setId(1L);
            state.setWorkspace(workspace);
            state.setBybitAdId("ad-123");
            state.setPublished(true);
            when(workspaces.findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc())
                    .thenReturn(List.of(workspace));
            when(states.findByWorkspace(workspace)).thenReturn(Optional.of(state));
            when(states.findById(1L)).thenReturn(Optional.of(state));
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(1)).run();
                return null;
            }).when(manager).withPublicationLock(any(), any());
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            service = new AdUnpublishRecoveryService(
                    workspaces, withdrawals, states, new AdUnpublishStateService(states, clock),
                    manager, gateway, new BybitCredentialsContext(), mock(WorkspaceSecretService.class), clock
            );
        }
    }
}
