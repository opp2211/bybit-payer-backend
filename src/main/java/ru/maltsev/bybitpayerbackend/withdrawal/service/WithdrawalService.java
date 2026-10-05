package ru.maltsev.bybitpayerbackend.withdrawal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.ai.dto.AiChatAgentResponse;
import ru.maltsev.bybitpayerbackend.ai.service.AiChatAgentService;
import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bank.service.BankService;
import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementPreview;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitOrderWatcher;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.common.service.PublicIdGenerator;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.CreateWithdrawalRequest;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalAdvertisementPreviewResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalDetailsResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalAmountMode;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalPaymentRules;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalEventRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;

@Service
@Slf4j
public class WithdrawalService {

    private final WithdrawalRequestRepository withdrawalRepository;
    private final WithdrawalEventRepository eventRepository;
    private final BybitChatService chatService;
    private final EmailReceiptCheckRepository receiptCheckRepository;
    private final BybitOrderBindingRepository bindingRepository;
    private final WithdrawalInputNormalizer normalizer;
    private final WithdrawalEventService eventService;
    private final AdvertisementManager advertisementManager;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final BankService bankService;
    private final WithdrawalMapper mapper;
    private final CurrentUserService currentUserService;
    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceSecretService workspaceSecretService;
    private final PublicIdGenerator publicIdGenerator;
    private final AuditService auditService;
    private final AiChatAgentService aiChatAgentService;
    private final BybitOrderWatcher bybitOrderWatcher;
    private final BusinessProperties businessProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public WithdrawalService(
            WithdrawalRequestRepository withdrawalRepository,
            WithdrawalEventRepository eventRepository,
            BybitChatService chatService,
            EmailReceiptCheckRepository receiptCheckRepository,
            BybitOrderBindingRepository bindingRepository,
            WithdrawalInputNormalizer normalizer,
            WithdrawalEventService eventService,
            AdvertisementManager advertisementManager,
            BybitGateway bybitGateway,
            BybitCredentialsContext bybitCredentialsContext,
            BankService bankService,
            WithdrawalMapper mapper,
            CurrentUserService currentUserService,
            WorkspaceAccessService workspaceAccessService,
            WorkspaceSecretService workspaceSecretService,
            PublicIdGenerator publicIdGenerator,
            AuditService auditService,
            AiChatAgentService aiChatAgentService,
            BybitOrderWatcher bybitOrderWatcher,
            BusinessProperties businessProperties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.withdrawalRepository = withdrawalRepository;
        this.eventRepository = eventRepository;
        this.chatService = chatService;
        this.receiptCheckRepository = receiptCheckRepository;
        this.bindingRepository = bindingRepository;
        this.normalizer = normalizer;
        this.eventService = eventService;
        this.advertisementManager = advertisementManager;
        this.bybitGateway = bybitGateway;
        this.bybitCredentialsContext = bybitCredentialsContext;
        this.bankService = bankService;
        this.mapper = mapper;
        this.currentUserService = currentUserService;
        this.workspaceAccessService = workspaceAccessService;
        this.workspaceSecretService = workspaceSecretService;
        this.publicIdGenerator = publicIdGenerator;
        this.auditService = auditService;
        this.aiChatAgentService = aiChatAgentService;
        this.bybitOrderWatcher = bybitOrderWatcher;
        this.businessProperties = businessProperties;
        this.clock = clock;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public WithdrawalService(
            WithdrawalRequestRepository withdrawalRepository,
            WithdrawalEventRepository eventRepository,
            BybitChatService chatService,
            EmailReceiptCheckRepository receiptCheckRepository,
            BybitOrderBindingRepository bindingRepository,
            WithdrawalInputNormalizer normalizer,
            WithdrawalEventService eventService,
            AdvertisementManager advertisementManager,
            BybitGateway bybitGateway,
            BankService bankService,
            WithdrawalMapper mapper,
            Clock clock
    ) {
        this(
                withdrawalRepository,
                eventRepository,
                chatService,
                receiptCheckRepository,
                bindingRepository,
                normalizer,
                eventService,
                advertisementManager,
                bybitGateway,
                new BybitCredentialsContext(),
                bankService,
                mapper,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new BusinessProperties(),
                clock,
                null
        );
    }

    @Transactional
    public WithdrawalResponse create(String workspacePublicId, CreateWithdrawalRequest request) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        ensureWorkspaceBybitAdConfigured(workspace);
        WithdrawalAmountMode amountMode = WithdrawalAmountMode.effective(request.amountMode());
        WithdrawalAmountSelection amountSelection = normalizeAmountSelection(request, amountMode);
        PayerBankType payerBankType = PayerBankType.effective(request.payerBankType());
        WithdrawalMethod withdrawalMethod = WithdrawalMethod.effective(request.withdrawalMethod());
        WithdrawalPaymentRules.validateMethod(payerBankType, withdrawalMethod);
        if (WithdrawalPaymentRules.isAutoReleaseEnabled(payerBankType, withdrawalMethod)
                && !StringUtils.hasText(workspace.getReceiptEmail())) {
            throw BusinessException.badRequest("Workspace receipt email is required for T-Bank auto withdrawals");
        }
        WithdrawalRequisites requisites = normalizeRequisites(request, withdrawalMethod);
        boolean thirdPartyTransfer = Boolean.TRUE.equals(request.thirdPartyTransfer());
        boolean requireSenderFirstParty = Boolean.TRUE.equals(request.requireSenderFirstParty());

        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setPublicId(publicIdGenerator.generate(withdrawalRepository::existsByPublicId));
        withdrawal.setWorkspace(workspace);
        withdrawal.setCreatedBy(currentUser);
        withdrawal.setAmountMode(amountMode);
        withdrawal.setAmountRub(amountSelection.amountRub());
        withdrawal.setAmountMinRub(amountSelection.amountMinRub());
        withdrawal.setAmountMaxRub(amountSelection.amountMaxRub());
        withdrawal.setRecipientPhone(requisites.recipientPhone());
        withdrawal.setRecipientBank(requisites.recipientBank());
        withdrawal.setRecipientName(requisites.recipientName());
        withdrawal.setRecipientCardNumber(requisites.recipientCardNumber());
        withdrawal.setRecipientAccountNumber(requisites.recipientAccountNumber());
        withdrawal.setRecipientCardTbank(requisites.recipientCardTbank());
        withdrawal.setThirdPartyTransfer(thirdPartyTransfer);
        withdrawal.setPayerBankType(payerBankType);
        withdrawal.setRequireSenderFirstParty(requireSenderFirstParty);
        withdrawal.setWithdrawalMethod(withdrawalMethod);
        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setAttentionRequired(false);
        withdrawal.setCompletionSeen(true);
        withdrawal.setQueueGroupKey(queueGroupKey(withdrawal));
        withdrawal.setCreatedAt(Instant.now(clock));
        withdrawal = withdrawalRepository.save(withdrawal);
        eventService.add(withdrawal, WithdrawalEventType.WITHDRAWAL_CREATED, "Withdrawal request created", currentUser);
        auditService.add(currentUser, workspace, "WITHDRAWAL_CREATED", "WITHDRAWAL", withdrawal.getPublicId(), null);

        advertisementManager.rebuildPublication(workspace);
        WithdrawalRequestEntity refreshed = getRequiredEntity(workspace, withdrawal.getPublicId());
        log.info(
                "Withdrawal created: id={}, amountMode={}, amountMinRub={}, amountMaxRub={}, bank={}, status={}",
                refreshed.getId(),
                refreshed.getAmountMode(),
                refreshed.getAmountMinRub(),
                refreshed.getAmountMaxRub(),
                refreshed.getRecipientBank() == null ? null : refreshed.getRecipientBank().getCode(),
                refreshed.getStatus()
        );
        return mapper.toResponse(refreshed);
    }

    @Transactional(readOnly = true)
    public WithdrawalAdvertisementPreviewResponse previewAdvertisement(
            String workspacePublicId,
            CreateWithdrawalRequest request
    ) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        ensureWorkspaceBybitAdConfigured(workspace);
        WithdrawalAmountMode amountMode = WithdrawalAmountMode.effective(request.amountMode());
        WithdrawalAmountSelection amountSelection = normalizeAmountSelection(request, amountMode);
        PayerBankType payerBankType = PayerBankType.effective(request.payerBankType());
        WithdrawalMethod withdrawalMethod = WithdrawalMethod.effective(request.withdrawalMethod());
        WithdrawalPaymentRules.validateMethod(payerBankType, withdrawalMethod);
        boolean thirdPartyTransfer = Boolean.TRUE.equals(request.thirdPartyTransfer());
        boolean requireSenderFirstParty = Boolean.TRUE.equals(request.requireSenderFirstParty());
        boolean recipientCardTbank = withdrawalMethod == WithdrawalMethod.CARD_NUMBER
                && Boolean.TRUE.equals(request.recipientCardTbank());
        BybitManagedAdStateEntity currentState = advertisementManager.getCurrentState(workspace);
        AdvertisementPreview preview = advertisementManager.buildSingleWithdrawalPreview(
                amountMode,
                amountSelection.amountRub(),
                amountSelection.amountMinRub(),
                amountSelection.amountMaxRub(),
                payerBankType,
                withdrawalMethod,
                thirdPartyTransfer,
                recipientCardTbank,
                requireSenderFirstParty,
                currentState.getLastRate()
        );

        return new WithdrawalAdvertisementPreviewResponse(
                preview.rate(),
                preview.minRub(),
                preview.maxRub(),
                preview.amountMinRub(),
                preview.amountMaxRub(),
                preview.quantityUsdt(),
                preview.description()
        );
    }

    @Transactional(readOnly = true)
    public List<WithdrawalResponse> getActive(String workspacePublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        return withdrawalRepository.findByWorkspaceAndStatusInOrderByCreatedAtDescIdDesc(
                        workspace,
                        WithdrawalStatus.ACTIVE_STATUSES
                ).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WithdrawalResponse> getCompleted(String workspacePublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        return withdrawalRepository.findByWorkspaceAndStatusOrderByCompletedAtDescIdDesc(
                        workspace,
                        WithdrawalStatus.COMPLETED
                ).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WithdrawalDetailsResponse getDetails(String workspacePublicId, String publicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = getRequiredEntity(workspace, publicId);
        return new WithdrawalDetailsResponse(
                mapper.toResponse(withdrawal),
                eventRepository.findByWithdrawalRequest_IdOrderByCreatedAtAscIdAsc(withdrawal.getId()).stream()
                        .map(mapper::toEventResponse)
                        .toList(),
                chatService.getMessages(workspace, withdrawal),
                receiptCheckRepository.findByWithdrawalRequest_IdOrderByCreatedAtDescIdDesc(withdrawal.getId()).stream()
                        .map(mapper::toReceiptCheckResponse)
                        .toList(),
                aiChatAgentService == null ? AiChatAgentResponse.absent() : aiChatAgentService.getResponse(withdrawal)
        );
    }

    public WithdrawalResponse cancel(String workspacePublicId, String publicId) {
        CancellationPreparation preparation = inTransaction(
                () -> prepareCancellation(workspacePublicId, publicId)
        );
        if (!preparation.safetyCheckRequired()) {
            advertisementManager.rebuildPublication(preparation.workspace());
            return preparation.response();
        }

        try {
            advertisementManager.rebuildPublication(preparation.workspace());
            awaitCancellationGracePeriod();
            bybitOrderWatcher.pollActiveOrders(preparation.workspace());
        } catch (RuntimeException exception) {
            inTransaction(() -> {
                abortCancellation(workspacePublicId, publicId, exception);
                return null;
            });
            rebuildPublicationSafely(preparation.workspace());
            throw exception;
        }

        return inTransaction(() -> finishCancellation(workspacePublicId, publicId));
    }

    private CancellationPreparation prepareCancellation(String workspacePublicId, String publicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = getRequiredEntityForUpdate(workspace, publicId);
        WithdrawalStatus previousStatus = withdrawal.getStatus();
        if (!previousStatus.canBeCancelled()) {
            throw BusinessException.conflict("Заявку нельзя отменить в статусе " + previousStatus.getTitle());
        }

        if (previousStatus == WithdrawalStatus.IN_WORK) {
            withdrawal.setStatus(WithdrawalStatus.CANCELLATION_PENDING);
            withdrawal.setQueuePosition(null);
            eventService.add(
                    withdrawal,
                    WithdrawalEventType.WITHDRAWAL_CANCELLATION_STARTED,
                    "Withdrawal cancellation safety check started",
                    currentUser
            );
            withdrawalRepository.save(withdrawal);
            log.info("Withdrawal cancellation safety check started: id={}", withdrawal.getId());
            return new CancellationPreparation(workspace, true, null);
        }

        WithdrawalResponse response = cancelWithdrawal(withdrawal, currentUser, workspace, previousStatus);
        return new CancellationPreparation(workspace, false, response);
    }

    private WithdrawalResponse finishCancellation(String workspacePublicId, String publicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = getRequiredEntityForUpdate(workspace, publicId);
        if (withdrawal.getStatus() != WithdrawalStatus.CANCELLATION_PENDING) {
            throw BusinessException.conflict(
                    "Отмена остановлена: к заявке успел привязаться Bybit-ордер"
            );
        }
        return cancelWithdrawal(
                withdrawal,
                currentUser,
                workspace,
                WithdrawalStatus.CANCELLATION_PENDING
        );
    }

    private WithdrawalResponse cancelWithdrawal(
            WithdrawalRequestEntity withdrawal,
            UserEntity currentUser,
            WorkspaceEntity workspace,
            WithdrawalStatus previousStatus
    ) {
        withdrawal.setStatus(WithdrawalStatus.CANCELLED);
        withdrawal.setCancelledAt(Instant.now(clock));
        withdrawal.setQueuePosition(null);
        eventService.add(
                withdrawal,
                WithdrawalEventType.WITHDRAWAL_CANCELLED,
                "Withdrawal request cancelled by user",
                currentUser
        );
        WithdrawalRequestEntity saved = withdrawalRepository.save(withdrawal);
        auditService.add(
                currentUser,
                workspace,
                "WITHDRAWAL_CANCELLED",
                "WITHDRAWAL",
                withdrawal.getPublicId(),
                null
        );
        log.info(
                "Withdrawal cancelled: id={}, previousStatus={}, amountRub={}",
                saved.getId(),
                previousStatus,
                saved.getAmountRub()
        );
        return mapper.toResponse(saved);
    }

    private void abortCancellation(String workspacePublicId, String publicId, RuntimeException cause) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = getRequiredEntityForUpdate(workspace, publicId);
        if (withdrawal.getStatus() != WithdrawalStatus.CANCELLATION_PENDING) {
            return;
        }
        withdrawal.setStatus(WithdrawalStatus.IN_WORK);
        withdrawal.setLastError(cause.getMessage());
        withdrawalRepository.save(withdrawal);
        eventService.add(
                withdrawal,
                WithdrawalEventType.WITHDRAWAL_CANCELLATION_ABORTED,
                "Withdrawal cancellation safety check failed",
                currentUser
        );
        log.warn(
                "Withdrawal cancellation safety check aborted: id={}, message={}",
                withdrawal.getId(),
                cause.getMessage()
        );
    }

    private void awaitCancellationGracePeriod() {
        Duration gracePeriod = businessProperties.getWithdrawalCancellationGracePeriod();
        if (gracePeriod == null || gracePeriod.isZero() || gracePeriod.isNegative()) {
            return;
        }
        try {
            Thread.sleep(gracePeriod.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Withdrawal cancellation safety check interrupted", exception);
        }
    }

    private void rebuildPublicationSafely(WorkspaceEntity workspace) {
        try {
            advertisementManager.rebuildPublication(workspace);
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to restore managed advertisement after cancellation safety check: workspace={}, message={}",
                    workspace.getPublicId(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    @Transactional
    public WithdrawalResponse markCompletedSeen(String workspacePublicId, String publicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = getRequiredEntity(workspace, publicId);
        if (withdrawal.getStatus() != WithdrawalStatus.COMPLETED) {
            throw BusinessException.conflict("Only completed withdrawal can be marked as seen");
        }
        withdrawal.setCompletionSeen(true);
        eventService.add(withdrawal, WithdrawalEventType.COMPLETION_SEEN, "User confirmed completed withdrawal", currentUser);
        WithdrawalRequestEntity saved = withdrawalRepository.save(withdrawal);
        log.debug("Withdrawal completion marked as seen: id={}", saved.getId());
        return mapper.toResponse(saved);
    }

    private WithdrawalAmountSelection normalizeAmountSelection(
            CreateWithdrawalRequest request,
            WithdrawalAmountMode amountMode
    ) {
        return switch (amountMode) {
            case FIXED -> {
                BigDecimal amountRub = normalizer.normalizeAmount(request.amountRub(), "amountRub");
                yield new WithdrawalAmountSelection(amountRub, amountRub, amountRub);
            }
            case RANGE -> {
                BigDecimal amountMinRub = normalizer.normalizeAmount(request.amountMinRub(), "amountMinRub");
                BigDecimal amountMaxRub = normalizer.normalizeAmount(request.amountMaxRub(), "amountMaxRub");
                if (amountMaxRub.compareTo(amountMinRub) <= 0) {
                    throw BusinessException.badRequest("amountMaxRub must be greater than amountMinRub");
                }
                yield new WithdrawalAmountSelection(null, amountMinRub, amountMaxRub);
            }
        };
    }

    private String queueGroupKey(WithdrawalRequestEntity withdrawal) {
        if (WithdrawalAmountMode.effective(withdrawal.getAmountMode()) == WithdrawalAmountMode.RANGE) {
            return WithdrawalPaymentRules.rangeQueueGroupKey(withdrawal.getPublicId());
        }
        return WithdrawalPaymentRules.queueGroupKey(
                withdrawal.getPayerBankType(),
                withdrawal.getWithdrawalMethod(),
                effectiveThirdPartyTransfer(withdrawal),
                withdrawal.isRecipientCardTbank(),
                withdrawal.isRequireSenderFirstParty()
        );
    }

    private boolean effectiveThirdPartyTransfer(WithdrawalRequestEntity withdrawal) {
        return withdrawal.getWithdrawalMethod() == null || withdrawal.isThirdPartyTransfer();
    }

    @Transactional
    public WithdrawalResponse release(Long id) {
        WithdrawalRequestEntity withdrawal = withdrawalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + id));
        if (!withdrawal.getStatus().canBeReleased() || withdrawal.getBybitOrderId() == null) {
            throw BusinessException.conflict(
                    "Withdrawal cannot be released in status " + withdrawal.getStatus()
            );
        }
        var binding = bindingRepository
                .findByWithdrawalRequest_IdAndStatus(withdrawal.getId(), OrderBindingStatus.ACTIVE)
                .orElseThrow(() -> BusinessException.conflict("Active Bybit order binding not found"));

        bybitGateway.releaseOrder(withdrawal.getBybitOrderId());
        binding.setStatus(OrderBindingStatus.RELEASED);
        bindingRepository.save(binding);

        withdrawal.setStatus(WithdrawalStatus.COMPLETED);
        withdrawal.setCompletedAt(Instant.now(clock));
        withdrawal.setCompletionSeen(false);
        withdrawal.setAttentionRequired(false);
        withdrawal.setLastError(null);
        withdrawal.setLastWarning(null);
        eventService.add(
                withdrawal,
                WithdrawalEventType.MANUAL_RELEASE_SUCCEEDED,
                "Bybit order released manually"
        );
        WithdrawalRequestEntity saved = withdrawalRepository.save(withdrawal);
        return mapper.toResponse(saved);
    }

    @Transactional
    public WithdrawalResponse release(String workspacePublicId, String publicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = getRequiredEntity(workspace, publicId);
        if (!withdrawal.getStatus().canBeReleased() || withdrawal.getBybitOrderId() == null) {
            throw BusinessException.conflict(
                    "Withdrawal cannot be released in status " + withdrawal.getStatus()
            );
        }
        var binding = bindingRepository
                .findByWithdrawalRequest_IdAndStatus(withdrawal.getId(), OrderBindingStatus.ACTIVE)
                .orElseThrow(() -> BusinessException.conflict("Active Bybit order binding not found"));

        bybitCredentialsContext.runWith(
                workspaceSecretService.bybitCredentials(workspace),
                () -> bybitGateway.releaseOrder(withdrawal.getBybitOrderId())
        );
        binding.setStatus(OrderBindingStatus.RELEASED);
        bindingRepository.save(binding);

        withdrawal.setStatus(WithdrawalStatus.COMPLETED);
        withdrawal.setCompletedAt(Instant.now(clock));
        withdrawal.setCompletionSeen(false);
        withdrawal.setAttentionRequired(false);
        withdrawal.setLastError(null);
        withdrawal.setLastWarning(null);
        eventService.add(
                withdrawal,
                WithdrawalEventType.MANUAL_RELEASE_SUCCEEDED,
                "Bybit order released manually",
                currentUser
        );
        WithdrawalRequestEntity saved = withdrawalRepository.save(withdrawal);
        auditService.add(currentUser, workspace, "WITHDRAWAL_RELEASED", "WITHDRAWAL", withdrawal.getPublicId(), null);
        log.warn(
                "Withdrawal released manually: id={}, orderId={}",
                saved.getId(),
                saved.getBybitOrderId()
        );
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public EmailReceiptCheckEntity getReceiptPdf(String workspacePublicId, String withdrawalPublicId, Long receiptId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = getRequiredEntity(workspace, withdrawalPublicId);
        EmailReceiptCheckEntity receipt = receiptCheckRepository
                .findByIdAndWithdrawalRequest_Id(receiptId, withdrawal.getId())
                .orElseThrow(() -> new EntityNotFoundException("Receipt PDF not found: " + receiptId));
        if (receipt.getPdfContent() == null || receipt.getPdfContent().length == 0) {
            throw new EntityNotFoundException("Receipt PDF content is not available: " + receiptId);
        }
        return receipt;
    }

    private WithdrawalRequestEntity getRequiredEntity(WorkspaceEntity workspace, String publicId) {
        return withdrawalRepository.findByWorkspaceAndPublicId(workspace, publicId)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + publicId));
    }

    private WithdrawalRequestEntity getRequiredEntityForUpdate(WorkspaceEntity workspace, String publicId) {
        return withdrawalRepository.findForUpdateByWorkspaceAndPublicId(workspace, publicId)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + publicId));
    }

    private <T> T inTransaction(Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        return transactionTemplate.execute(status -> action.get());
    }

    private record CancellationPreparation(
            WorkspaceEntity workspace,
            boolean safetyCheckRequired,
            WithdrawalResponse response
    ) {
    }

    private void ensureWorkspaceBybitAdConfigured(WorkspaceEntity workspace) {
        if (!StringUtils.hasText(workspace.getBybitP2pAdId())) {
            throw BusinessException.badRequest("Workspace Bybit P2P ad id is required");
        }
    }

    private WithdrawalRequisites normalizeRequisites(
            CreateWithdrawalRequest request,
            WithdrawalMethod withdrawalMethod
    ) {
        return switch (withdrawalMethod) {
            case SBP -> new WithdrawalRequisites(
                    normalizer.normalizePhone(request.recipientPhone()),
                    bankService.getEnabledByExternalValue(request.recipientBank()),
                    normalizer.normalizeRecipientName(request.recipientName()),
                    null,
                    null,
                    false
            );
            case CARD_NUMBER -> normalizeCardNumberRequisites(request);
            case ACCOUNT_NUMBER -> new WithdrawalRequisites(
                    null,
                    null,
                    normalizer.normalizeRecipientName(request.recipientName()),
                    null,
                    normalizer.normalizeAccountNumber(request.recipientAccountNumber()),
                    false
            );
        };
    }

    private WithdrawalRequisites normalizeCardNumberRequisites(CreateWithdrawalRequest request) {
        boolean recipientCardTbank = Boolean.TRUE.equals(request.recipientCardTbank());
        return new WithdrawalRequisites(
                null,
                null,
                recipientCardTbank
                        ? normalizer.normalizeRecipientName(request.recipientName())
                        : null,
                normalizer.normalizeCardNumber(request.recipientCardNumber()),
                null,
                recipientCardTbank
        );
    }

    private record WithdrawalRequisites(
            String recipientPhone,
            BankEntity recipientBank,
            String recipientName,
            String recipientCardNumber,
            String recipientAccountNumber,
            boolean recipientCardTbank
    ) {
    }

    private record WithdrawalAmountSelection(
            BigDecimal amountRub,
            BigDecimal amountMinRub,
            BigDecimal amountMaxRub
    ) {
    }

}
