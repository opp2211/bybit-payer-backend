package ru.maltsev.bybitpayerbackend.bybit.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

public interface BybitOrderBindingRepository extends JpaRepository<BybitOrderBindingEntity, Long> {

    Optional<BybitOrderBindingEntity> findByWorkspaceAndBybitOrderId(WorkspaceEntity workspace, String bybitOrderId);

    Optional<BybitOrderBindingEntity> findByBybitOrderId(String bybitOrderId);

    Optional<BybitOrderBindingEntity> findByWithdrawalRequest_IdAndStatus(Long withdrawalRequestId, OrderBindingStatus status);

    List<BybitOrderBindingEntity> findAllByWorkspaceAndStatus(WorkspaceEntity workspace, OrderBindingStatus status);

    List<BybitOrderBindingEntity> findAllByStatus(OrderBindingStatus status);

    @EntityGraph(attributePaths = {"workspace", "withdrawalRequest.workspace"})
    List<BybitOrderBindingEntity> findTop20ByStatusAndCompletionThanksRequestedTrueAndCompletionThanksProcessedFalseOrderById(
            OrderBindingStatus status
    );

    @Modifying
    @Transactional
    @Query("""
            update BybitOrderBindingEntity binding
            set binding.completionThanksProcessed = true
            where binding.id = :id
              and binding.status = :status
              and binding.completionThanksRequested = true
              and binding.completionThanksProcessed = false
            """)
    int claimCompletionThanks(@Param("id") Long id, @Param("status") OrderBindingStatus status);
}
