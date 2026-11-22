package ru.maltsev.bybitpayerbackend.workspace.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitAccountInfo;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentials;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.common.service.PublicIdGenerator;
import ru.maltsev.bybitpayerbackend.receipt.service.TinkoffReceiptMailService;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.user.repository.UserRepository;
import ru.maltsev.bybitpayerbackend.user.service.UserNormalizer;
import ru.maltsev.bybitpayerbackend.workspace.dto.AddWorkspaceMemberRequest;
import ru.maltsev.bybitpayerbackend.workspace.dto.CreateWorkspaceRequest;
import ru.maltsev.bybitpayerbackend.workspace.dto.WorkspaceMemberResponse;
import ru.maltsev.bybitpayerbackend.workspace.dto.WorkspaceResponse;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceMemberEntity;
import ru.maltsev.bybitpayerbackend.workspace.model.WorkspaceMemberRole;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceMemberRepository;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final WorkspaceAccessService accessService;
    private final WorkspaceSecretService secretService;
    private final PublicIdGenerator publicIdGenerator;
    private final UserNormalizer userNormalizer;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final BybitGateway bybitGateway;
    private final TinkoffReceiptMailService mailService;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> listCurrentUserWorkspaces() {
        UserEntity currentUser = currentUserService.currentUser();
        return memberRepository.findByUserAndWorkspace_DeletedAtIsNullOrderByWorkspace_CreatedAtAscWorkspace_IdAsc(currentUser)
                .stream()
                .map(member -> toResponse(member.getWorkspace(), currentUser))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse getDetails(String publicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = accessService.getAccessibleWorkspace(publicId, currentUser);
        return toResponse(workspace, currentUser);
    }

    @Transactional
    public WorkspaceResponse create(CreateWorkspaceRequest request) {
        UserEntity currentUser = currentUserService.currentUser();
        String apiKeyHash = secretService.apiKeyHash(request.bybitApiKey());
        if (workspaceRepository.existsByBybitApiKeyHashAndBybitP2pAdId(
                apiKeyHash,
                request.bybitP2pAdId().trim()
        )) {
            throw BusinessException.conflict("Workspace with this Bybit API key and P2P ad id already exists");
        }

        BybitCredentials credentials = new BybitCredentials(
                request.bybitApiKey().trim(),
                request.bybitApiSecret().trim(),
                request.bybitP2pAdId().trim()
        );
        BybitAccountInfo bybitAccountInfo = bybitCredentialsContext.callWith(credentials, () -> {
            var readiness = bybitGateway.checkReadiness();
            if (!readiness.available()) {
                throw BusinessException.conflict("Bybit connection check failed: " + readiness.message());
            }
            return bybitGateway.fetchAccountInfo();
        });
        mailService.checkConnection(new ru.maltsev.bybitpayerbackend.receipt.service.ReceiptMailbox(
                request.imapHost().trim(),
                request.imapPort(),
                request.imapUsername().trim(),
                request.imapPassword().trim()
        ));

        Instant now = Instant.now(clock);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setPublicId(publicIdGenerator.generate(workspaceRepository::existsByPublicId));
        workspace.setName(request.name().trim());
        workspace.setOwner(currentUser);
        workspace.setBybitP2pAdId(request.bybitP2pAdId().trim());
        applyBybitAccountInfo(workspace, bybitAccountInfo);
        workspace.setReceiptEmail(trimToNull(request.receiptEmail()));
        workspace.setImapHost(request.imapHost().trim());
        workspace.setImapPort(request.imapPort());
        workspace.setImapUsername(request.imapUsername().trim());
        workspace.setEnabled(true);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        secretService.storeSecrets(workspace, request.bybitApiKey(), request.bybitApiSecret(), request.imapPassword());
        workspace = workspaceRepository.save(workspace);
        addMember(workspace, currentUser, WorkspaceMemberRole.OWNER, currentUser, now);
        auditService.add(currentUser, workspace, "WORKSPACE_CREATED", "WORKSPACE", workspace.getPublicId(), null);
        log.info("Workspace created: publicId={}, owner={}", workspace.getPublicId(), currentUser.getUsername());
        return toResponse(workspace, currentUser);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> listMembers(String workspacePublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = accessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        return memberRepository.findByWorkspaceOrderByCreatedAtAscIdAsc(workspace).stream()
                .map(this::toMemberResponse)
                .toList();
    }

    @Transactional
    public WorkspaceMemberResponse addMember(String workspacePublicId, AddWorkspaceMemberRequest request) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = accessService.getOwnedWorkspace(workspacePublicId, currentUser);
        UserEntity invited = findUser(request.lookup());
        if (memberRepository.existsByWorkspaceAndUser(workspace, invited)) {
            throw BusinessException.conflict("User is already a workspace member");
        }
        WorkspaceMemberEntity member = addMember(
                workspace,
                invited,
                WorkspaceMemberRole.MEMBER,
                currentUser,
                Instant.now(clock)
        );
        auditService.add(
                currentUser,
                workspace,
                "WORKSPACE_MEMBER_ADDED",
                "USER",
                invited.getPublicId(),
                "{\"username\":\"" + invited.getUsername() + "\"}"
        );
        return toMemberResponse(member);
    }

    @Transactional
    public void removeMember(String workspacePublicId, String userPublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = accessService.getOwnedWorkspace(workspacePublicId, currentUser);
        UserEntity target = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userPublicId));
        if (workspace.getOwner().getId().equals(target.getId())) {
            throw BusinessException.conflict("Workspace owner cannot be removed");
        }
        WorkspaceMemberEntity member = memberRepository.findByWorkspaceAndUser(workspace, target)
                .orElseThrow(() -> new EntityNotFoundException("Workspace member not found"));
        memberRepository.delete(member);
        auditService.add(currentUser, workspace, "WORKSPACE_MEMBER_REMOVED", "USER", target.getPublicId(), null);
    }

    @Transactional
    public void softDelete(String workspacePublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = accessService.getOwnedWorkspace(workspacePublicId, currentUser);
        bybitCredentialsContext.runWith(secretService.bybitCredentials(workspace), () -> {
            try {
                bybitGateway.unpublishManagedAd(workspace.getBybitP2pAdId());
            } catch (RuntimeException exception) {
                throw BusinessException.conflict("Workspace deletion blocked because Bybit ad could not be unpublished: "
                        + exception.getMessage());
            }
        });
        workspace.setEnabled(false);
        workspace.setDeletedAt(Instant.now(clock));
        workspace.setUpdatedAt(Instant.now(clock));
        workspaceRepository.save(workspace);
        auditService.add(currentUser, workspace, "WORKSPACE_DELETED", "WORKSPACE", workspace.getPublicId(), null);
    }

    WorkspaceMemberEntity addMember(
            WorkspaceEntity workspace,
            UserEntity user,
            WorkspaceMemberRole role,
            UserEntity createdBy,
            Instant now
    ) {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspace(workspace);
        member.setUser(user);
        member.setRole(role);
        member.setCreatedBy(createdBy);
        member.setCreatedAt(now);
        return memberRepository.save(member);
    }

    public WorkspaceResponse toResponse(WorkspaceEntity workspace, UserEntity currentUser) {
        WorkspaceMemberRole role = workspace.getOwner().getId().equals(currentUser.getId())
                ? WorkspaceMemberRole.OWNER
                : WorkspaceMemberRole.MEMBER;
        return new WorkspaceResponse(
                workspace.getPublicId(),
                workspace.getName(),
                workspace.getOwner().getPublicId(),
                workspace.getOwner().getUsername(),
                role.name(),
                workspace.getBybitP2pAdId(),
                workspace.getBybitNickname(),
                workspace.getReceiptEmail(),
                workspace.getImapHost(),
                workspace.getImapPort(),
                workspace.getImapUsername(),
                workspace.isEnabled(),
                workspace.getCreatedAt()
        );
    }

    private void applyBybitAccountInfo(WorkspaceEntity workspace, BybitAccountInfo accountInfo) {
        if (accountInfo == null) {
            return;
        }
        workspace.setBybitUserId(trimToNull(accountInfo.userId()));
        workspace.setBybitAccountId(trimToNull(accountInfo.accountId()));
        workspace.setBybitNickname(trimToNull(accountInfo.nickname()));
    }

    private WorkspaceMemberResponse toMemberResponse(WorkspaceMemberEntity member) {
        UserEntity user = member.getUser();
        return new WorkspaceMemberResponse(
                user.getPublicId(),
                user.getUsername(),
                user.getEmail(),
                member.getRole().name(),
                member.getCreatedAt()
        );
    }

    private UserEntity findUser(String lookup) {
        String normalized = userNormalizer.normalizeLookup(lookup);
        return userRepository.findByPublicId(lookup.trim().toUpperCase(java.util.Locale.ROOT))
                .or(() -> userRepository.findByUsernameNormalized(normalized))
                .or(() -> userRepository.findByEmailNormalized(normalized))
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + lookup));
    }

    private String trimToNull(String value) {
        if (value == null || !org.springframework.util.StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
