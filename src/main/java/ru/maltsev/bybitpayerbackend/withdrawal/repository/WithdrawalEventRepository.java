package ru.maltsev.bybitpayerbackend.withdrawal.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalSoundEventResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalEventEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

public interface WithdrawalEventRepository extends JpaRepository<WithdrawalEventEntity, Long> {

    List<WithdrawalEventEntity> findByWithdrawalRequest_IdOrderByCreatedAtAscIdAsc(Long withdrawalRequestId);

    @Query("""
            select new ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalSoundEventResponse(event.id, event.eventType)
            from WithdrawalEventEntity event
            where event.withdrawalRequest.workspace = :workspace
              and event.eventType in :eventTypes
              and event.createdAt >= :since
            order by event.createdAt desc, event.id desc
            """)
    List<WithdrawalSoundEventResponse> findRecentSoundEvents(
            @Param("workspace") WorkspaceEntity workspace,
            @Param("eventTypes") Collection<WithdrawalEventType> eventTypes,
            @Param("since") Instant since,
            Pageable pageable
    );
}
