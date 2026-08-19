package ru.maltsev.bybitpayerbackend.withdrawal.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequestEntity, Long> {

    boolean existsByPublicId(String publicId);

    Optional<WithdrawalRequestEntity> findByWorkspaceAndPublicId(WorkspaceEntity workspace, String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select withdrawal
            from WithdrawalRequestEntity withdrawal
            where withdrawal.workspace = :workspace
              and withdrawal.publicId = :publicId
            """)
    Optional<WithdrawalRequestEntity> findForUpdateByWorkspaceAndPublicId(
            @Param("workspace") WorkspaceEntity workspace,
            @Param("publicId") String publicId
    );

    List<WithdrawalRequestEntity> findByWorkspaceAndStatusInOrderByCreatedAtAscIdAsc(
            WorkspaceEntity workspace,
            Collection<WithdrawalStatus> statuses
    );

    List<WithdrawalRequestEntity> findByWorkspaceAndStatusInOrderByCreatedAtDescIdDesc(
            WorkspaceEntity workspace,
            Collection<WithdrawalStatus> statuses
    );

    List<WithdrawalRequestEntity> findByWorkspaceAndStatusOrderByCompletedAtDescIdDesc(
            WorkspaceEntity workspace,
            WithdrawalStatus status
    );

    List<WithdrawalRequestEntity> findByWorkspaceAndStatusOrderByCreatedAtAscIdAsc(
            WorkspaceEntity workspace,
            WithdrawalStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select withdrawal
            from WithdrawalRequestEntity withdrawal
            where withdrawal.workspace = :workspace
              and withdrawal.status in :statuses
            order by withdrawal.createdAt asc, withdrawal.id asc
            """)
    List<WithdrawalRequestEntity> findForBindingByWorkspaceAndStatusInOrderByCreatedAtAscIdAsc(
            @Param("workspace") WorkspaceEntity workspace,
            @Param("statuses") Collection<WithdrawalStatus> statuses
    );

    List<WithdrawalRequestEntity> findByWorkspaceAndStatusAndAmountRubOrderByCreatedAtAscIdAsc(
            WorkspaceEntity workspace,
            WithdrawalStatus status,
            BigDecimal amountRub
    );

    List<WithdrawalRequestEntity> findByStatusAndOrderFoundAtBefore(
            WithdrawalStatus status,
            Instant threshold
    );

    List<WithdrawalRequestEntity> findByStatusAndVerificationStartedAtBefore(
            WithdrawalStatus status,
            Instant threshold
    );

    Optional<WithdrawalRequestEntity> findByWorkspaceAndBybitOrderId(WorkspaceEntity workspace, String bybitOrderId);

    List<WithdrawalRequestEntity> findByStatusInOrderByCreatedAtAscIdAsc(Collection<WithdrawalStatus> statuses);

    List<WithdrawalRequestEntity> findByStatusInOrderByCreatedAtDescIdDesc(Collection<WithdrawalStatus> statuses);

    List<WithdrawalRequestEntity> findByStatusOrderByCompletedAtDescIdDesc(WithdrawalStatus status);

    List<WithdrawalRequestEntity> findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select withdrawal
            from WithdrawalRequestEntity withdrawal
            where withdrawal.status in :statuses
            order by withdrawal.createdAt asc, withdrawal.id asc
            """)
    List<WithdrawalRequestEntity> findForBindingByStatusInOrderByCreatedAtAscIdAsc(
            @Param("statuses") Collection<WithdrawalStatus> statuses
    );

    List<WithdrawalRequestEntity> findByStatusAndAmountRubOrderByCreatedAtAscIdAsc(
            WithdrawalStatus status,
            BigDecimal amountRub
    );

    Optional<WithdrawalRequestEntity> findByBybitOrderId(String bybitOrderId);
}
