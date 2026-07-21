package ru.maltsev.bybitpayerbackend.workspace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, Long> {

    boolean existsByPublicId(String publicId);

    boolean existsByBybitApiKeyHashAndBybitP2pAdId(String bybitApiKeyHash, String bybitP2pAdId);

    List<WorkspaceEntity> findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc();

    Optional<WorkspaceEntity> findByPublicIdAndDeletedAtIsNull(String publicId);
}
