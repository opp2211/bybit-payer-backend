package ru.maltsev.bybitpayerbackend.receipt.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffMailReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptData;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.receipt.entity.IgnoredEmailReceiptEntity;
import ru.maltsev.bybitpayerbackend.receipt.exception.ReceiptPdfParseException;
import ru.maltsev.bybitpayerbackend.receipt.model.ReceiptVerificationStatus;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.receipt.repository.IgnoredEmailReceiptRepository;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalPaymentRules;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

@Service
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ReceiptVerificationWorker {

    private final ReceiptMailProperties mailProperties;
    private final TinkoffReceiptMailService mailService;
    private final TinkoffReceiptPdfParser pdfParser;
    private final EmailReceiptCheckRepository receiptCheckRepository;
    private final IgnoredEmailReceiptRepository ignoredReceiptRepository;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final BybitOrderBindingRepository bindingRepository;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final WorkspaceSecretService workspaceSecretService;
    private final WorkspaceRepository workspaceRepository;
    private final WithdrawalEventService eventService;
    private final Clock clock;

    public ReceiptVerificationWorker(
            ReceiptMailProperties mailProperties,
            TinkoffReceiptMailService mailService,
            TinkoffReceiptPdfParser pdfParser,
            EmailReceiptCheckRepository receiptCheckRepository,
            IgnoredEmailReceiptRepository ignoredReceiptRepository,
            WithdrawalRequestRepository withdrawalRepository,
            BybitOrderBindingRepository bindingRepository,
            BybitGateway bybitGateway,
            WithdrawalEventService eventService,
            Clock clock
    ) {
        this(
                mailProperties,
                mailService,
                pdfParser,
                receiptCheckRepository,
                ignoredReceiptRepository,
                withdrawalRepository,
                bindingRepository,
                bybitGateway,
                new BybitCredentialsContext(),
                null,
                null,
                eventService,
                clock
        );
    }

    @Scheduled(fixedDelayString = "${receipt.mail.poll-interval:5s}")
    @Transactional
    public void verifyPendingPayments() {
        if (!mailProperties.isEnabled()) {
            return;
        }

        if (workspaceRepository == null) {
            verifyPendingPaymentsLegacy();
            return;
        }

        for (WorkspaceEntity workspace : workspaceRepository.findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc()) {
            verifyPendingPayments(workspace);
        }
    }

    private void verifyPendingPaymentsLegacy() {
        List<WithdrawalRequestEntity> withdrawals = withdrawalRepository
                .findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.PAYMENT_VERIFICATION);
        for (WithdrawalRequestEntity withdrawal : withdrawals) {
            verifyWithdrawal(null, withdrawal);
        }
    }

    private void verifyPendingPayments(WorkspaceEntity workspace) {
        List<WithdrawalRequestEntity> withdrawals = withdrawalRepository
                .findByWorkspaceAndStatusOrderByCreatedAtAscIdAsc(workspace, WithdrawalStatus.PAYMENT_VERIFICATION);
        if (!withdrawals.isEmpty()) {
            log.debug(
                    "Withdrawals awaiting receipt verification: workspace={}, count={}",
                    workspace.getPublicId(),
                    withdrawals.size()
            );
        }
        for (WithdrawalRequestEntity withdrawal : withdrawals) {
            verifyWithdrawal(workspace, withdrawal);
        }
    }

    private void verifyWithdrawal(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (!autoReleaseEnabled(withdrawal)) {
            return;
        }

        var verifiedCheck = receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        withdrawal.getId(),
                        withdrawal.getBybitOrderId(),
                        ReceiptVerificationStatus.VERIFIED
        );
        if (verifiedCheck.isPresent()) {
            retryVerifiedReceipt(workspace, withdrawal, verifiedCheck.get());
            return;
        }

        TinkoffReceiptVerificationRequest request = verificationRequest(withdrawal);

        var ignoredReceiptKeys = ignoredReceiptRepository
                .findReceiptKeysByWithdrawalRequestId(withdrawal.getId());
        List<TinkoffMailReceiptValidationResult> results = workspace == null
                ? mailService.findForWithdrawal(request, ignoredReceiptKeys)
                : mailService.findForWithdrawal(request, ignoredReceiptKeys, workspaceSecretService.receiptMailbox(workspace));
        for (TinkoffMailReceiptValidationResult result : results) {
            if (!result.recipientPhoneMatches()) {
                rememberIgnoredReceipt(withdrawal, result);
                continue;
            }

            String error = result.valid()
                    ? senderError(workspace, withdrawal, result.receipt())
                    : String.join("; ", result.errors());
            EmailReceiptCheckEntity check = saveCheck(withdrawal, result, error);
            if (error == null) {
                releaseWithdrawal(workspace, withdrawal, check);
            } else {
                markVerificationFailed(withdrawal, error);
            }
            return;
        }
    }

    private void retryVerifiedReceipt(
            WorkspaceEntity workspace,
            WithdrawalRequestEntity withdrawal,
            EmailReceiptCheckEntity check
    ) {
        if (withdrawal.isRequireSenderFirstParty()) {
            String error = senderError(workspace, withdrawal, readSavedReceipt(check));
            if (error != null) {
                check.setVerificationStatus(ReceiptVerificationStatus.FAILED);
                check.setVerificationError(error);
                receiptCheckRepository.save(check);
                markVerificationFailed(withdrawal, error);
                return;
            }
        }
        releaseWithdrawal(workspace, withdrawal, check);
    }

    private TinkoffReceiptData readSavedReceipt(EmailReceiptCheckEntity check) {
        byte[] pdf = check.getPdfContent();
        if (pdf == null || pdf.length == 0) {
            return null;
        }
        try {
            return pdfParser.parse(pdf).data();
        } catch (ReceiptPdfParseException exception) {
            log.warn("Could not read saved receipt for sender verification: receiptCheckId={}", check.getId());
            return null;
        }
    }

    private String senderError(
            WorkspaceEntity workspace,
            WithdrawalRequestEntity withdrawal,
            TinkoffReceiptData receipt
    ) {
        if (!withdrawal.isRequireSenderFirstParty()) {
            return null;
        }
        if (receipt == null || !StringUtils.hasText(receipt.sender())) {
            return "В чеке не найдено поле «Отправитель»";
        }
        String bybitName = withdrawal.getBybitCounterpartyVerifiedName();
        if (!StringUtils.hasText(bybitName)) {
            bybitName = fetchCounterpartyName(workspace, withdrawal.getBybitOrderId());
        }
        return TinkoffReceiptValidator.firstPartySenderError(receipt.sender(), bybitName);
    }

    private String fetchCounterpartyName(WorkspaceEntity workspace, String orderId) {
        if (!StringUtils.hasText(orderId)) {
            return null;
        }
        try {
            if (workspace == null) {
                return bybitGateway.fetchOrderCounterpartyVerifiedName(orderId).orElse(null);
            }
            return bybitCredentialsContext.callWith(
                    workspaceSecretService.bybitCredentials(workspace),
                    () -> bybitGateway.fetchOrderCounterpartyVerifiedName(orderId).orElse(null)
            );
        } catch (RuntimeException exception) {
            log.debug("Bybit name unavailable for receipt verification: orderId={}", orderId);
            return null;
        }
    }

    private void rememberIgnoredReceipt(
            WithdrawalRequestEntity withdrawal,
            TinkoffMailReceiptValidationResult result
    ) {
        if (ignoredReceiptRepository.existsByWithdrawalRequest_IdAndReceiptKey(
                withdrawal.getId(),
                result.receiptKey()
        )) {
            return;
        }

        IgnoredEmailReceiptEntity ignored = new IgnoredEmailReceiptEntity();
        ignored.setWithdrawalRequest(withdrawal);
        ignored.setReceiptKey(result.receiptKey());
        ignored.setEmailMessageId(result.messageId());
        ignored.setPdfFilename(result.attachmentName());
        ignored.setCreatedAt(Instant.now(clock));
        ignoredReceiptRepository.save(ignored);
        log.debug(
                "Receipt ignored for withdrawal because requisites do not match: withdrawalId={}, receiptKey={}",
                withdrawal.getId(),
                result.receiptKey()
        );
    }

    private EmailReceiptCheckEntity saveCheck(
            WithdrawalRequestEntity withdrawal,
            TinkoffMailReceiptValidationResult result,
            String error
    ) {
        TinkoffReceiptData receipt = result.receipt();
        EmailReceiptCheckEntity check = new EmailReceiptCheckEntity();
        check.setWithdrawalRequest(withdrawal);
        check.setBybitOrderId(withdrawal.getBybitOrderId());
        check.setEmailMessageId(result.messageId());
        check.setEmailFrom(result.from());
        check.setEmailSubject(result.subject());
        check.setEmailReceivedAt(result.receivedAt());
        check.setPdfFilename(result.attachmentName());
        check.setPdfContent(result.pdfContent());
        if (receipt != null) {
            check.setParsedStatus(receipt.status());
            check.setParsedAmountRub(receipt.amount());
            check.setParsedRecipientPhone(receipt.phone());
            check.setParsedRecipientBank(receipt.bank());
            check.setParsedRecipientName(receipt.recipient());
            check.setParsedRecipientCard(receipt.card());
        }
        check.setVerificationStatus(error == null ? ReceiptVerificationStatus.VERIFIED : ReceiptVerificationStatus.FAILED);
        check.setVerificationError(error);
        check.setCreatedAt(Instant.now(clock));
        return receiptCheckRepository.save(check);
    }

    private void releaseWithdrawal(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal, EmailReceiptCheckEntity check) {
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            markVerificationFailed(withdrawal, "Verified receipt has no linked Bybit order");
            return;
        }

        try {
            if (workspace == null) {
                bybitGateway.releaseOrder(withdrawal.getBybitOrderId());
            } else {
                bybitCredentialsContext.runWith(
                        workspaceSecretService.bybitCredentials(workspace),
                        () -> bybitGateway.releaseOrder(withdrawal.getBybitOrderId())
                );
            }
            withdrawal.setStatus(WithdrawalStatus.COMPLETED);
            withdrawal.setCompletedAt(Instant.now(clock));
            withdrawal.setCompletionSeen(false);
            withdrawal.setAttentionRequired(false);
            withdrawal.setLastError(null);
            withdrawal.setLastWarning(null);
            withdrawalRepository.save(withdrawal);

            bindingRepository.findByWithdrawalRequest_IdAndStatus(withdrawal.getId(), OrderBindingStatus.ACTIVE)
                    .ifPresent(binding -> {
                        binding.setStatus(OrderBindingStatus.RELEASED);
                        bindingRepository.save(binding);
                    });

            eventService.add(withdrawal, WithdrawalEventType.VERIFICATION_SUCCEEDED, "Receipt verification succeeded");
            eventService.add(withdrawal, WithdrawalEventType.RELEASE_SUCCEEDED, "Bybit order released", "{\"receiptCheckId\":" + check.getId() + "}");
            log.info(
                    "Withdrawal completed after receipt verification: withdrawalId={}, orderId={}, receiptCheckId={}",
                    withdrawal.getId(),
                    withdrawal.getBybitOrderId(),
                    check.getId()
            );
        } catch (Exception exception) {
            withdrawal.setAttentionRequired(true);
            withdrawal.setLastError(exception.getMessage());
            withdrawalRepository.save(withdrawal);
            eventService.add(withdrawal, WithdrawalEventType.RELEASE_FAILED, "Bybit release failed: " + exception.getMessage());
            log.error(
                    "Bybit order release failed: withdrawalId={}, orderId={}, message={}",
                    withdrawal.getId(),
                    withdrawal.getBybitOrderId(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private void markVerificationFailed(WithdrawalRequestEntity withdrawal, String reason) {
        withdrawal.setStatus(WithdrawalStatus.ERROR);
        withdrawal.setAttentionRequired(true);
        withdrawal.setLastWarning("Найден чек с реквизитами заявки, но данные не совпали: " + reason);
        withdrawalRepository.save(withdrawal);
        eventService.add(withdrawal, WithdrawalEventType.VERIFICATION_FAILED, "Receipt verification failed: " + reason);
        log.warn(
                "Receipt verification failed: withdrawalId={}, orderId={}",
                withdrawal.getId(),
                withdrawal.getBybitOrderId()
        );
    }

    private boolean autoReleaseEnabled(WithdrawalRequestEntity withdrawal) {
        return WithdrawalPaymentRules.isAutoReleaseEnabled(
                withdrawal.getPayerBankType(),
                withdrawal.getWithdrawalMethod()
        );
    }

    private TinkoffReceiptVerificationRequest verificationRequest(WithdrawalRequestEntity withdrawal) {
        WithdrawalMethod withdrawalMethod = WithdrawalMethod.effective(withdrawal.getWithdrawalMethod());
        return switch (withdrawalMethod) {
            case SBP -> new TinkoffReceiptVerificationRequest(
                    expectedReceiptAmount(withdrawal),
                    withdrawal.getRecipientName(),
                    withdrawal.getRecipientPhone(),
                    withdrawal.getRecipientBank().getTitle()
            );
            case CARD_NUMBER -> new TinkoffReceiptVerificationRequest(
                    expectedReceiptAmount(withdrawal),
                    withdrawal.getRecipientName(),
                    null,
                    null,
                    WithdrawalMethod.CARD_NUMBER,
                    withdrawal.getRecipientCardNumber(),
                    withdrawal.isRecipientCardTbank()
            );
            case ACCOUNT_NUMBER -> throw new IllegalStateException("Account-number withdrawal is not auto-released");
        };
    }

    private java.math.BigDecimal expectedReceiptAmount(WithdrawalRequestEntity withdrawal) {
        return withdrawal.getBybitOrderAmountRub() == null
                ? withdrawal.getAmountRub()
                : withdrawal.getBybitOrderAmountRub();
    }
}
