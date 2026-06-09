package ru.maltsev.bybitpayerbackend.receipt.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffMailReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptData;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.receipt.entity.IgnoredEmailReceiptEntity;
import ru.maltsev.bybitpayerbackend.receipt.model.ReceiptVerificationStatus;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.receipt.repository.IgnoredEmailReceiptRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

@Service
@Slf4j
public class ReceiptVerificationWorker {

    private final ReceiptMailProperties mailProperties;
    private final TinkoffReceiptMailService mailService;
    private final EmailReceiptCheckRepository receiptCheckRepository;
    private final IgnoredEmailReceiptRepository ignoredReceiptRepository;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final BybitOrderBindingRepository bindingRepository;
    private final BybitGateway bybitGateway;
    private final WithdrawalEventService eventService;
    private final Clock clock;

    public ReceiptVerificationWorker(
            ReceiptMailProperties mailProperties,
            TinkoffReceiptMailService mailService,
            EmailReceiptCheckRepository receiptCheckRepository,
            IgnoredEmailReceiptRepository ignoredReceiptRepository,
            WithdrawalRequestRepository withdrawalRepository,
            BybitOrderBindingRepository bindingRepository,
            BybitGateway bybitGateway,
            WithdrawalEventService eventService,
            Clock clock
    ) {
        this.mailProperties = mailProperties;
        this.mailService = mailService;
        this.receiptCheckRepository = receiptCheckRepository;
        this.ignoredReceiptRepository = ignoredReceiptRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.bindingRepository = bindingRepository;
        this.bybitGateway = bybitGateway;
        this.eventService = eventService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${receipt.mail.poll-interval:5s}")
    @Transactional
    public void verifyPendingPayments() {
        if (!mailProperties.isEnabled()) {
            return;
        }

        List<WithdrawalRequestEntity> withdrawals = withdrawalRepository
                .findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.PAYMENT_VERIFICATION);
        if (!withdrawals.isEmpty()) {
            log.debug("Withdrawals awaiting receipt verification: count={}", withdrawals.size());
        }
        for (WithdrawalRequestEntity withdrawal : withdrawals) {
            verifyWithdrawal(withdrawal);
        }
    }

    private void verifyWithdrawal(WithdrawalRequestEntity withdrawal) {
        var verifiedCheck = receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        withdrawal.getId(),
                        withdrawal.getBybitOrderId(),
                        ReceiptVerificationStatus.VERIFIED
                );
        if (verifiedCheck.isPresent()) {
            releaseWithdrawal(withdrawal, verifiedCheck.get());
            return;
        }

        TinkoffReceiptVerificationRequest request = new TinkoffReceiptVerificationRequest(
                withdrawal.getAmountRub(),
                withdrawal.getRecipientName(),
                withdrawal.getRecipientPhone(),
                withdrawal.getRecipientBank().getTitle()
        );

        var ignoredReceiptKeys = ignoredReceiptRepository
                .findReceiptKeysByWithdrawalRequestId(withdrawal.getId());
        List<TinkoffMailReceiptValidationResult> results = mailService.findForWithdrawal(
                request,
                ignoredReceiptKeys
        );
        for (TinkoffMailReceiptValidationResult result : results) {
            if (!result.recipientPhoneMatches()) {
                rememberIgnoredReceipt(withdrawal, result);
                continue;
            }

            EmailReceiptCheckEntity check = saveCheck(withdrawal, result);
            if (result.valid()) {
                releaseWithdrawal(withdrawal, check);
                return;
            }
            markVerificationFailed(withdrawal, String.join("; ", result.errors()));
            return;
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
                "Receipt ignored for withdrawal because phone does not match: withdrawalId={}, receiptKey={}",
                withdrawal.getId(),
                result.receiptKey()
        );
    }

    private EmailReceiptCheckEntity saveCheck(WithdrawalRequestEntity withdrawal, TinkoffMailReceiptValidationResult result) {
        TinkoffReceiptData receipt = result.receipt();
        EmailReceiptCheckEntity check = new EmailReceiptCheckEntity();
        check.setWithdrawalRequest(withdrawal);
        check.setBybitOrderId(withdrawal.getBybitOrderId());
        check.setEmailMessageId(result.messageId());
        check.setEmailFrom(result.from());
        check.setEmailSubject(result.subject());
        check.setEmailReceivedAt(result.receivedAt());
        check.setPdfFilename(result.attachmentName());
        if (receipt != null) {
            check.setParsedStatus(receipt.status());
            check.setParsedAmountRub(receipt.amount());
            check.setParsedRecipientPhone(receipt.phone());
            check.setParsedRecipientBank(receipt.bank());
            check.setParsedRecipientName(receipt.recipient());
        }
        check.setVerificationStatus(result.valid() ? ReceiptVerificationStatus.VERIFIED : ReceiptVerificationStatus.FAILED);
        check.setVerificationError(result.valid() ? null : String.join("; ", result.errors()));
        check.setCreatedAt(Instant.now(clock));
        return receiptCheckRepository.save(check);
    }

    private void releaseWithdrawal(WithdrawalRequestEntity withdrawal, EmailReceiptCheckEntity check) {
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            markVerificationFailed(withdrawal, "Verified receipt has no linked Bybit order");
            return;
        }

        try {
            bybitGateway.releaseOrder(withdrawal.getBybitOrderId());
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
        withdrawal.setLastWarning("Найден чек с номером телефона заявки, но данные не совпали: " + reason);
        withdrawalRepository.save(withdrawal);
        eventService.add(withdrawal, WithdrawalEventType.VERIFICATION_FAILED, "Receipt verification failed: " + reason);
        log.warn(
                "Receipt verification failed: withdrawalId={}, orderId={}",
                withdrawal.getId(),
                withdrawal.getBybitOrderId()
        );
    }
}
