package ru.maltsev.bybitpayerbackend.bybit.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

import java.util.Optional;

public interface BybitManagedAdStateRepository extends JpaRepository<BybitManagedAdStateEntity, Long> {

    Optional<BybitManagedAdStateEntity> findByWorkspace(WorkspaceEntity workspace);
}
