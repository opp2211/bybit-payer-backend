package ru.maltsev.bybitpayerbackend.workspace.service;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitAccountInfo;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;

@Service
@RequiredArgsConstructor
public class WorkspaceBybitIdentityService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceSecretService workspaceSecretService;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final BybitGateway bybitGateway;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WorkspaceBybitIdentity resolve(WorkspaceEntity workspace) {
        if (workspace == null) {
            return new WorkspaceBybitIdentity(null, null, null);
        }
        if (hasIdentity(workspace)) {
            return toIdentity(workspace);
        }

        WorkspaceEntity managed = workspaceRepository.findById(workspace.getId()).orElse(workspace);
        BybitAccountInfo accountInfo = bybitCredentialsContext.callWith(
                workspaceSecretService.bybitCredentials(managed),
                bybitGateway::fetchAccountInfo
        );
        apply(managed, accountInfo);
        apply(workspace, accountInfo);
        managed.setUpdatedAt(Instant.now(clock));
        workspaceRepository.save(managed);
        return toIdentity(managed);
    }

    private boolean hasIdentity(WorkspaceEntity workspace) {
        return StringUtils.hasText(workspace.getBybitUserId())
                || StringUtils.hasText(workspace.getBybitAccountId())
                || StringUtils.hasText(workspace.getBybitNickname());
    }

    private void apply(WorkspaceEntity workspace, BybitAccountInfo accountInfo) {
        if (accountInfo == null) {
            return;
        }
        workspace.setBybitUserId(trimToNull(accountInfo.userId()));
        workspace.setBybitAccountId(trimToNull(accountInfo.accountId()));
        workspace.setBybitNickname(trimToNull(accountInfo.nickname()));
    }

    private WorkspaceBybitIdentity toIdentity(WorkspaceEntity workspace) {
        return new WorkspaceBybitIdentity(
                workspace.getBybitUserId(),
                workspace.getBybitAccountId(),
                workspace.getBybitNickname()
        );
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
