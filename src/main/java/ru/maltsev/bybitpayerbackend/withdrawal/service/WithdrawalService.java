package ru.maltsev.bybitpayerbackend.withdrawal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bank.service.BankService;
import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementPreview;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.common.service.PublicIdGenerator;
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
    private final Clock clock;

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
            Clock clock
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
        this.clock = clock;
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
                clock
        );
    }

    @Transactional
    public WithdrawalResponse create(String workspacePublicId, CreateWithdrawalRequest request) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        BigDecimal amountRub = normalizer.normalizeAmount(request.amountRub());
        PayerBankType payerBankType = PayerBankType.effective(request.payerBankType());
        WithdrawalMethod withdrawalMethod = WithdrawalMethod.effective(request.withdrawalMethod());
        WithdrawalPaymentRules.validateMethod(payerBankType, withdrawalMethod);
        WithdrawalRequisites requisites = normalizeRequisites(request, withdrawalMethod);
        boolean thirdPartyTransfer = Boolean.TRUE.equals(request.thirdPartyTransfer());

        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setPublicId(publicIdGenerator.generate(withdrawalRepository::existsByPublicId));
        withdrawal.setWorkspace(workspace);
        withdrawal.setCreatedBy(currentUser);
        withdrawal.setAmountRub(amountRub);
        withdrawal.setRecipientPhone(requisites.recipientPhone());
        withdrawal.setRecipientBank(requisites.recipientBank());
        withdrawal.setRecipientName(requisites.recipientName());
        withdrawal.setRecipientCardNumber(requisites.recipientCardNumber());
        withdrawal.setRecipientAccountNumber(requisites.recipientAccountNumber());
        withdrawal.setRecipientCardTbank(requisites.recipientCardTbank());
        withdrawal.setThirdPartyTransfer(thirdPartyTransfer);
        withdrawal.setPayerBankType(payerBankType);
        withdrawal.setWithdrawalMethod(withdrawalMethod);
        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setAttentionRequired(false);
        withdrawal.setCompletionSeen(true);
        withdrawal.setQueueGroupKey(WithdrawalPaymentRules.queueGroupKey(
                payerBankType,
                withdrawalMethod,
                thirdPartyTransfer,
                requisites.recipientCardTbank()
        ));
        withdrawal.setCreatedAt(Instant.now(clock));
        withdrawal = withdrawalRepository.save(withdrawal);
        eventService.add(withdrawal, WithdrawalEventType.WITHDRAWAL_CREATED, "Withdrawal request created", currentUser);
        auditService.add(currentUser, workspace, "WITHDRAWAL_CREATED", "WITHDRAWAL", withdrawal.getPublicId(), null);

        advertisementManager.rebuildPublication(workspace);
        WithdrawalRequestEntity refreshed = getRequiredEntity(workspace, withdrawal.getPublicId());
        log.info(
                "Withdrawal created: id={}, amountRub={}, bank={}, status={}",
                refreshed.getId(),
                refreshed.getAmountRub(),
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
        BigDecimal amountRub = normalizer.normalizeAmount(request.amountRub());
        PayerBankType payerBankType = PayerBankType.effective(request.payerBankType());
        WithdrawalMethod withdrawalMethod = WithdrawalMethod.effective(request.withdrawalMethod());
        WithdrawalPaymentRules.validateMethod(payerBankType, withdrawalMethod);
        boolean thirdPartyTransfer = Boolean.TRUE.equals(request.thirdPartyTransfer());
        boolean recipientCardTbank = withdrawalMethod == WithdrawalMethod.CARD_NUMBER
                && Boolean.TRUE.equals(request.recipientCardTbank());
        BybitManagedAdStateEntity currentState = advertisementManager.getCurrentState(workspace);
        AdvertisementPreview preview = advertisementManager.buildSingleWithdrawalPreview(
                amountRub,
                payerBankType,
                withdrawalMethod,
                thirdPartyTransfer,
                recipientCardTbank,
                currentState.getLastRate()
        );

        return new WithdrawalAdvertisementPreviewResponse(
                preview.rate(),
                preview.minRub(),
                preview.maxRub(),
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
                        .toList()
        );
    }

    @Transactional
    public WithdrawalResponse cancel(String workspacePublicId, String publicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = getRequiredEntity(workspace, publicId);
        WithdrawalStatus previousStatus = withdrawal.getStatus();
        if (!previousStatus.canBeCancelled()) {
            throw BusinessException.conflict("Withdrawal cannot be cancelled in status " + previousStatus);
        }

        if (withdrawal.getStatus() == WithdrawalStatus.IN_WORK) {
            List<BybitP2pOrder> matchingOrders = bybitCredentialsContext.callWith(
                    workspaceSecretService.bybitCredentials(workspace),
                    () -> bybitGateway.fetchActiveOrders().stream()
                            .filter(order -> order.amountRub().compareTo(withdrawal.getAmountRub()) == 0)
                            .toList()
            );
            if (!matchingOrders.isEmpty()) {
                throw BusinessException.conflict("Cancellation refused because a matching Bybit order exists");
            }
        }

        withdrawal.setStatus(WithdrawalStatus.CANCELLED);
        withdrawal.setCancelledAt(Instant.now(clock));
        withdrawal.setQueuePosition(null);
        eventService.add(withdrawal, WithdrawalEventType.WITHDRAWAL_CANCELLED, "Withdrawal request cancelled by user", currentUser);
        withdrawalRepository.save(withdrawal);
        auditService.add(currentUser, workspace, "WITHDRAWAL_CANCELLED", "WITHDRAWAL", withdrawal.getPublicId(), null);
        advertisementManager.rebuildPublication(workspace);
        WithdrawalRequestEntity refreshed = getRequiredEntity(workspace, publicId);
        log.info(
                "Withdrawal cancelled: id={}, previousStatus={}, amountRub={}",
                refreshed.getId(),
                previousStatus,
                refreshed.getAmountRub()
        );
        return mapper.toResponse(refreshed);
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

}
