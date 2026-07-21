package ru.maltsev.bybitpayerbackend.workspace.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceMemberRepository;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;

@Service
@RequiredArgsConstructor
public class WorkspaceAccessService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;

    @Transactional(readOnly = true)
    public WorkspaceEntity getAccessibleWorkspace(String publicId, UserEntity user) {
        WorkspaceEntity workspace = getActiveWorkspace(publicId);
        if (!memberRepository.existsByWorkspaceAndUser(workspace, user)) {
            throw BusinessException.forbidden("Workspace access denied");
        }
        return workspace;
    }

    @Transactional(readOnly = true)
    public WorkspaceEntity getOwnedWorkspace(String publicId, UserEntity user) {
        WorkspaceEntity workspace = getAccessibleWorkspace(publicId, user);
        if (!workspace.getOwner().getId().equals(user.getId())) {
            throw BusinessException.forbidden("Only workspace owner can manage members and settings");
        }
        return workspace;
    }

    private WorkspaceEntity getActiveWorkspace(String publicId) {
        return workspaceRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace not found: " + publicId));
    }
}
