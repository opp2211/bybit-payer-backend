package ru.maltsev.bybitpayerbackend.withdrawal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bank.service.BankService;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.CreateWithdrawalRequest;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalDetailsResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalEventRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;

@Service
@Slf4j
@RequiredArgsConstructor
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
    private final BankService bankService;
    private final WithdrawalMapper mapper;
    private final Clock clock;

    @Transactional
    public WithdrawalResponse create(CreateWithdrawalRequest request) {
        BigDecimal amountRub = normalizer.normalizeAmount(request.amountRub());
        String phone = normalizer.normalizePhone(request.recipientPhone());
        BankEntity bank = bankService.getEnabledByExternalValue(request.recipientBank());
        String recipientName = normalizer.normalizeRecipientName(request.recipientName());

        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setAmountRub(amountRub);
        withdrawal.setRecipientPhone(phone);
        withdrawal.setRecipientBank(bank);
        withdrawal.setRecipientName(recipientName);
        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setAttentionRequired(false);
        withdrawal.setCompletionSeen(true);
        withdrawal.setQueueGroupKey(amountRub.stripTrailingZeros().toPlainString());
        withdrawal.setCreatedAt(Instant.now(clock));
        withdrawal = withdrawalRepository.save(withdrawal);
        eventService.add(withdrawal, WithdrawalEventType.WITHDRAWAL_CREATED, "Withdrawal request created");

        advertisementManager.rebuildPublication();
        WithdrawalRequestEntity refreshed = getRequiredEntity(withdrawal.getId());
        log.info(
                "Withdrawal created: id={}, amountRub={}, bank={}, status={}",
                refreshed.getId(),
                refreshed.getAmountRub(),
                refreshed.getRecipientBank().getCode(),
                refreshed.getStatus()
        );
        return mapper.toResponse(refreshed);
    }

    @Transactional(readOnly = true)
    public List<WithdrawalResponse> getActive() {
        return withdrawalRepository.findByStatusInOrderByCreatedAtDescIdDesc(WithdrawalStatus.ACTIVE_STATUSES).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WithdrawalResponse> getCompleted() {
        return withdrawalRepository.findByStatusOrderByCompletedAtDescIdDesc(WithdrawalStatus.COMPLETED).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WithdrawalDetailsResponse getDetails(Long id) {
        WithdrawalRequestEntity withdrawal = getRequiredEntity(id);
        return new WithdrawalDetailsResponse(
                mapper.toResponse(withdrawal),
                eventRepository.findByWithdrawalRequest_IdOrderByCreatedAtAscIdAsc(id).stream()
                        .map(mapper::toEventResponse)
                        .toList(),
                chatService.getMessages(withdrawal),
                receiptCheckRepository.findByWithdrawalRequest_IdOrderByCreatedAtDescIdDesc(id).stream()
                        .map(mapper::toReceiptCheckResponse)
                        .toList()
        );
    }

    @Transactional
    public WithdrawalResponse cancel(Long id) {
        WithdrawalRequestEntity withdrawal = getRequiredEntity(id);
        WithdrawalStatus previousStatus = withdrawal.getStatus();
        if (!previousStatus.canBeCancelled()) {
            throw BusinessException.conflict("Withdrawal cannot be cancelled in status " + previousStatus);
        }

        if (withdrawal.getStatus() == WithdrawalStatus.IN_WORK) {
            List<BybitP2pOrder> matchingOrders = bybitGateway.fetchActiveOrders().stream()
                    .filter(order -> order.amountRub().compareTo(withdrawal.getAmountRub()) == 0)
                    .toList();
            if (!matchingOrders.isEmpty()) {
                throw BusinessException.conflict("Cancellation refused because a matching Bybit order exists");
            }
        }

        withdrawal.setStatus(WithdrawalStatus.CANCELLED);
        withdrawal.setCancelledAt(Instant.now(clock));
        withdrawal.setQueuePosition(null);
        eventService.add(withdrawal, WithdrawalEventType.WITHDRAWAL_CANCELLED, "Withdrawal request cancelled by user");
        withdrawalRepository.save(withdrawal);
        advertisementManager.rebuildPublication();
        WithdrawalRequestEntity refreshed = getRequiredEntity(id);
        log.info(
                "Withdrawal cancelled: id={}, previousStatus={}, amountRub={}",
                refreshed.getId(),
                previousStatus,
                refreshed.getAmountRub()
        );
        return mapper.toResponse(refreshed);
    }

    @Transactional
    public WithdrawalResponse markCompletedSeen(Long id) {
        WithdrawalRequestEntity withdrawal = getRequiredEntity(id);
        if (withdrawal.getStatus() != WithdrawalStatus.COMPLETED) {
            throw BusinessException.conflict("Only completed withdrawal can be marked as seen");
        }
        withdrawal.setCompletionSeen(true);
        eventService.add(withdrawal, WithdrawalEventType.COMPLETION_SEEN, "User confirmed completed withdrawal");
        WithdrawalRequestEntity saved = withdrawalRepository.save(withdrawal);
        log.debug("Withdrawal completion marked as seen: id={}", saved.getId());
        return mapper.toResponse(saved);
    }

    @Transactional
    public WithdrawalResponse release(Long id) {
        WithdrawalRequestEntity withdrawal = getRequiredEntity(id);
        if (!withdrawal.getStatus().canBeReleased() || withdrawal.getBybitOrderId() == null) {
            throw BusinessException.conflict(
                    "Withdrawal cannot be released in status " + withdrawal.getStatus()
            );
        }
        var binding = bindingRepository
                .findByWithdrawalRequest_IdAndStatus(id, OrderBindingStatus.ACTIVE)
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
        log.warn(
                "Withdrawal released manually: id={}, orderId={}",
                saved.getId(),
                saved.getBybitOrderId()
        );
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public EmailReceiptCheckEntity getReceiptPdf(Long withdrawalId, Long receiptId) {
        EmailReceiptCheckEntity receipt = receiptCheckRepository
                .findByIdAndWithdrawalRequest_Id(receiptId, withdrawalId)
                .orElseThrow(() -> new EntityNotFoundException("Receipt PDF not found: " + receiptId));
        if (receipt.getPdfContent() == null || receipt.getPdfContent().length == 0) {
            throw new EntityNotFoundException("Receipt PDF content is not available: " + receiptId);
        }
        return receipt;
    }

    private WithdrawalRequestEntity getRequiredEntity(Long id) {
        return withdrawalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + id));
    }

}
