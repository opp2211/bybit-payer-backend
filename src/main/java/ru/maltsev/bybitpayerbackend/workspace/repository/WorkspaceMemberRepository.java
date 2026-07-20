package ru.maltsev.bybitpayerbackend.workspace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceMemberEntity;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMemberEntity, Long> {

    boolean existsByWorkspaceAndUser(WorkspaceEntity workspace, UserEntity user);

    List<WorkspaceMemberEntity> findByUserAndWorkspace_DeletedAtIsNullOrderByWorkspace_CreatedAtAscWorkspace_IdAsc(UserEntity user);

    List<WorkspaceMemberEntity> findByWorkspaceOrderByCreatedAtAscIdAsc(WorkspaceEntity workspace);

    Optional<WorkspaceMemberEntity> findByWorkspaceAndUser(WorkspaceEntity workspace, UserEntity user);
}
