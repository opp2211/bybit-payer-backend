package ru.maltsev.bybitpayerbackend.bybit.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bybit.entity.ForeignBybitOrderEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

public interface ForeignBybitOrderRepository extends JpaRepository<ForeignBybitOrderEntity, Long> {

    Optional<ForeignBybitOrderEntity> findByWorkspaceAndBybitOrderId(WorkspaceEntity workspace, String bybitOrderId);

    Optional<ForeignBybitOrderEntity> findByBybitOrderId(String bybitOrderId);

    List<ForeignBybitOrderEntity> findAllByWorkspaceOrderByUpdatedAtDescIdDesc(WorkspaceEntity workspace);

    List<ForeignBybitOrderEntity> findAllByOrderByUpdatedAtDescIdDesc();
}
