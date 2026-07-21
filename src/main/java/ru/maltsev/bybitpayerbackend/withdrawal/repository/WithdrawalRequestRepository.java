package ru.maltsev.bybitpayerbackend.withdrawal.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequestEntity, Long> {

    boolean existsByPublicId(String publicId);

    Optional<WithdrawalRequestEntity> findByWorkspaceAndPublicId(WorkspaceEntity workspace, String publicId);

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

    List<WithdrawalRequestEntity> findByStatusAndAmountRubOrderByCreatedAtAscIdAsc(
            WithdrawalStatus status,
            BigDecimal amountRub
    );

    Optional<WithdrawalRequestEntity> findByBybitOrderId(String bybitOrderId);
}
