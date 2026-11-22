package ru.maltsev.bybitpayerbackend.workspace.service;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;
import ru.maltsev.bybitpayerbackend.common.service.PublicIdGenerator;
import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.security.config.AuthProperties;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.user.model.UserRole;
import ru.maltsev.bybitpayerbackend.user.repository.UserRepository;
import ru.maltsev.bybitpayerbackend.user.service.UserNormalizer;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.model.WorkspaceMemberRole;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceMemberRepository;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;

@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class BootstrapWorkspaceService implements ApplicationRunner {

    private static final String LEGACY_WORKSPACE_NAME = "ExPrime";

    private final AuthProperties authProperties;
    private final BybitProperties bybitProperties;
    private final ReceiptMailProperties mailProperties;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserNormalizer userNormalizer;
    private final WorkspaceSecretService secretService;
    private final PublicIdGenerator publicIdGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UserEntity admin = ensureBootstrapAdmin();
        workspaceRepository.findAll().stream()
                .findFirst()
                .ifPresentOrElse(
                        workspace -> ensureOwnerMembership(workspace, workspace.getOwner()),
                        () -> maybeCreateLegacyWorkspace(admin)
                );
        assignLegacyRows();
    }

    private UserEntity ensureBootstrapAdmin() {
        String usernameNormalized = userNormalizer.normalizeUsername(authProperties.username());
        return userRepository.findByUsernameNormalized(usernameNormalized)
                .map(existing -> {
                    if (existing.getRole() != UserRole.ADMIN) {
                        existing.setRole(UserRole.ADMIN);
                        existing.setUpdatedAt(Instant.now(clock));
                        return userRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> createBootstrapAdmin(usernameNormalized));
    }

    private UserEntity createBootstrapAdmin(String usernameNormalized) {
        Instant now = Instant.now(clock);
        UserEntity user = new UserEntity();
        user.setPublicId(publicIdGenerator.generate(userRepository::existsByPublicId));
        user.setUsername(authProperties.username().trim());
        user.setUsernameNormalized(usernameNormalized);
        user.setEmail(authProperties.email().trim());
        user.setEmailNormalized(userNormalizer.normalizeEmail(authProperties.email()));
        user.setPasswordHash(authProperties.passwordHash());
        user.setRole(UserRole.ADMIN);
        user.setEmailVerified(false);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        UserEntity saved = userRepository.save(user);
        log.info("Bootstrap admin user created: username={}, publicId={}", saved.getUsername(), saved.getPublicId());
        return saved;
    }

    private void maybeCreateLegacyWorkspace(UserEntity owner) {
        if (!legacyBybitConfigured()) {
            log.info("Legacy Bybit config is incomplete, ExPrime workspace bootstrap skipped");
            return;
        }
        Instant now = Instant.now(clock);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setPublicId(publicIdGenerator.generate(workspaceRepository::existsByPublicId));
        workspace.setName(LEGACY_WORKSPACE_NAME);
        workspace.setOwner(owner);
        workspace.setBybitP2pAdId(bybitProperties.getP2pAdId().trim());
        workspace.setReceiptEmail(null);
        workspace.setImapHost(StringUtils.hasText(mailProperties.getHost()) ? mailProperties.getHost().trim() : null);
        workspace.setImapPort(mailProperties.getPort());
        workspace.setImapUsername(StringUtils.hasText(mailProperties.getUsername()) ? mailProperties.getUsername().trim() : null);
        workspace.setEnabled(true);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        secretService.storeSecrets(
                workspace,
                bybitProperties.getApiKey(),
                bybitProperties.getApiSecret(),
                StringUtils.hasText(mailProperties.getPassword()) ? mailProperties.getPassword() : "not-configured"
        );
        workspace = workspaceRepository.save(workspace);
        ensureOwnerMembership(workspace, owner);
        log.info("Legacy workspace bootstrapped: name={}, publicId={}", workspace.getName(), workspace.getPublicId());
    }

    private void ensureOwnerMembership(WorkspaceEntity workspace, UserEntity owner) {
        if (memberRepository.existsByWorkspaceAndUser(workspace, owner)) {
            return;
        }
        WorkspaceMemberEntityFactory.create(memberRepository, workspace, owner, WorkspaceMemberRole.OWNER, owner, Instant.now(clock));
    }

    private void assignLegacyRows() {
        workspaceRepository.findAll().stream().findFirst().ifPresent(workspace -> {
            Long workspaceId = workspace.getId();
            jdbcTemplate.update("update withdrawal_requests set workspace_id = ? where workspace_id is null", workspaceId);
            jdbcTemplate.update("update bybit_order_bindings set workspace_id = ? where workspace_id is null", workspaceId);
            jdbcTemplate.update("update bybit_managed_ad_state set workspace_id = ? where workspace_id is null", workspaceId);
            jdbcTemplate.update("update foreign_bybit_orders set workspace_id = ? where workspace_id is null", workspaceId);
            assignWithdrawalPublicIds();
        });
    }

    private void assignWithdrawalPublicIds() {
        jdbcTemplate.queryForList("select id from withdrawal_requests where public_id is null", Long.class)
                .forEach(id -> jdbcTemplate.update(
                        "update withdrawal_requests set public_id = ? where id = ?",
                        publicIdGenerator.generate(value -> countWithdrawalPublicId(value) > 0),
                        id
                ));
    }

    private int countWithdrawalPublicId(String publicId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from withdrawal_requests where public_id = ?",
                Integer.class,
                publicId
        );
        return count == null ? 0 : count;
    }

    private boolean legacyBybitConfigured() {
        return StringUtils.hasText(bybitProperties.getApiKey())
                && StringUtils.hasText(bybitProperties.getApiSecret())
                && StringUtils.hasText(bybitProperties.getP2pAdId());
    }

    private static class WorkspaceMemberEntityFactory {

        private static void create(
                WorkspaceMemberRepository repository,
                WorkspaceEntity workspace,
                UserEntity user,
                WorkspaceMemberRole role,
                UserEntity createdBy,
                Instant now
        ) {
            ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceMemberEntity member =
                    new ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceMemberEntity();
            member.setWorkspace(workspace);
            member.setUser(user);
            member.setRole(role);
            member.setCreatedBy(createdBy);
            member.setCreatedAt(now);
            repository.save(member);
        }
    }
}
