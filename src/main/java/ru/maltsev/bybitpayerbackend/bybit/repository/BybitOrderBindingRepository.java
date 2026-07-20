package ru.maltsev.bybitpayerbackend.bybit.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

public interface BybitOrderBindingRepository extends JpaRepository<BybitOrderBindingEntity, Long> {

    Optional<BybitOrderBindingEntity> findByWorkspaceAndBybitOrderId(WorkspaceEntity workspace, String bybitOrderId);

    Optional<BybitOrderBindingEntity> findByBybitOrderId(String bybitOrderId);

    Optional<BybitOrderBindingEntity> findByWithdrawalRequest_IdAndStatus(Long withdrawalRequestId, OrderBindingStatus status);

    List<BybitOrderBindingEntity> findAllByWorkspaceAndStatus(WorkspaceEntity workspace, OrderBindingStatus status);

    List<BybitOrderBindingEntity> findAllByStatus(OrderBindingStatus status);
}
