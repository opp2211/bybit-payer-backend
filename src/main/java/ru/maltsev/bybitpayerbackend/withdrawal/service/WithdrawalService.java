package ru.maltsev.bybitpayerbackend.withdrawal.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bank.service.BankService;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitChatMessageLogRepository;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
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
public class WithdrawalService {

    private final WithdrawalRequestRepository withdrawalRepository;
    private final WithdrawalEventRepository eventRepository;
    private final BybitChatMessageLogRepository chatMessageLogRepository;
    private final EmailReceiptCheckRepository receiptCheckRepository;
    private final WithdrawalInputNormalizer normalizer;
    private final WithdrawalEventService eventService;
    private final AdvertisementManager advertisementManager;
    private final BybitGateway bybitGateway;
    private final BankService bankService;
    private final WithdrawalMapper mapper;
    private final Clock clock;

    public WithdrawalService(
            WithdrawalRequestRepository withdrawalRepository,
            WithdrawalEventRepository eventRepository,
            BybitChatMessageLogRepository chatMessageLogRepository,
            EmailReceiptCheckRepository receiptCheckRepository,
            WithdrawalInputNormalizer normalizer,
            WithdrawalEventService eventService,
            AdvertisementManager advertisementManager,
            BybitGateway bybitGateway,
            BankService bankService,
            WithdrawalMapper mapper,
            Clock clock
    ) {
        this.withdrawalRepository = withdrawalRepository;
        this.eventRepository = eventRepository;
        this.chatMessageLogRepository = chatMessageLogRepository;
        this.receiptCheckRepository = receiptCheckRepository;
        this.normalizer = normalizer;
        this.eventService = eventService;
        this.advertisementManager = advertisementManager;
        this.bybitGateway = bybitGateway;
        this.bankService = bankService;
        this.mapper = mapper;
        this.clock = clock;
    }

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
                chatMessageLogRepository.findByWithdrawalRequest_IdOrderByMessageIndexAsc(id).stream()
                        .map(mapper::toChatMessageResponse)
                        .toList(),
                receiptCheckRepository.findByWithdrawalRequest_IdOrderByCreatedAtDescIdDesc(id).stream()
                        .map(mapper::toReceiptCheckResponse)
                        .toList()
        );
    }

    @Transactional
    public WithdrawalResponse cancel(Long id) {
        WithdrawalRequestEntity withdrawal = getRequiredEntity(id);
        WithdrawalStatus previousStatus = withdrawal.getStatus();
        if (!withdrawal.getStatus().canBeCancelled()) {
            throw BusinessException.conflict("Withdrawal cannot be cancelled in status " + withdrawal.getStatus());
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

    private WithdrawalRequestEntity getRequiredEntity(Long id) {
        return withdrawalRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + id));
    }

}
