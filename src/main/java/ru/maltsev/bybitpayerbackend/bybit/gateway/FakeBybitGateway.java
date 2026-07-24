package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;

@Component
@Profile("local")
@Slf4j
public class FakeBybitGateway implements BybitGateway {

    private static final BigDecimal REFERENCE_RATE = new BigDecimal("100.00");
    private static final BigDecimal AVAILABLE_USDT_BALANCE = new BigDecimal("100000.00");
    private static final String WAITING_BUYER_PAY = "10";
    private static final String WAITING_SELLER_RELEASE = "20";
    private static final String CANCELLED = "40";
    private static final String FINISHED = "50";
    private static final String FAKE_BUYER_NICKNAME = "Fake Buyer";
    private static final String SELLER_NICKNAME = "Seller";
    private static final String FAKE_BUYER_USER_ID = "fake-buyer";
    private static final String FAKE_BUYER_ACCOUNT_ID = "fake-buyer-account";
    private static final String SELLER_USER_ID = "seller";
    private static final String SELLER_ACCOUNT_ID = "seller-account";
    private static final String TEXT_CONTENT_TYPE = "str";
    private static final int TEXT_MESSAGE_TYPE = 1;

    private final Clock clock;
    private final BusinessProperties businessProperties;
    private final Map<String, SimulatedAd> ads = new ConcurrentHashMap<>();
    private final Map<String, SimulatedOrder> orders = new ConcurrentHashMap<>();
    private final AtomicLong orderSequence = new AtomicLong(1000);
    private final AtomicLong messageSequence = new AtomicLong(1000);

    public FakeBybitGateway(Clock clock, BusinessProperties businessProperties) {
        this.clock = clock;
        this.businessProperties = businessProperties;
    }

    @Override
    public BybitReadiness checkReadiness() {
        return new BybitReadiness(
                true,
                "FAKE",
                "Local fake Bybit gateway is active",
                AVAILABLE_USDT_BALANCE
        );
    }

    @Override
    public BigDecimal fetchReferenceRate() {
        return REFERENCE_RATE;
    }

    @Override
    public BigDecimal fetchReferenceRate(int adIndex) {
        return REFERENCE_RATE;
    }

    @Override
    public BigDecimal fetchAvailableUsdtBalance() {
        return AVAILABLE_USDT_BALANCE;
    }

    @Override
    public List<BybitP2pOrder> fetchActiveOrders() {
        return activeSimulatorOrders().stream()
                .map(SimulatedOrder::toBybitOrder)
                .toList();
    }

    @Override
    public BybitAccountInfo fetchAccountInfo() {
        return new BybitAccountInfo(SELLER_USER_ID, SELLER_ACCOUNT_ID, SELLER_NICKNAME);
    }

    @Override
    public Optional<BybitP2pOrder> fetchOrder(String bybitOrderId) {
        return Optional.ofNullable(orders.get(bybitOrderId))
                .map(SimulatedOrder::toBybitOrder);
    }

    @Override
    public List<BybitChatMessage> fetchChatMessages(String bybitOrderId) {
        SimulatedOrder order = orders.get(bybitOrderId);
        if (order == null) {
            return List.of();
        }

        return order.messages().stream()
                .map(message -> new BybitChatMessage(
                        message.id(),
                        message.message(),
                        message.userId(),
                        TEXT_MESSAGE_TYPE,
                        message.createdAt(),
                        TEXT_CONTENT_TYPE,
                        order.bybitOrderId(),
                        message.messageUuid(),
                        message.nickname(),
                        message.roleType(),
                        message.accountId(),
                        0,
                        ""
                ))
                .toList();
    }

    @Override
    public void updateManagedAd(AdUpdateCommand command) {
        ensureAdId(command.bybitAdId());
        if (!command.published()) {
            unpublishManagedAd(command.bybitAdId());
            return;
        }

        SimulatedAd ad = new SimulatedAd(
                command.bybitAdId(),
                true,
                command.rate(),
                command.minRub(),
                command.maxRub(),
                command.quantityUsdt(),
                command.description(),
                Instant.now(clock)
        );
        ads.put(command.bybitAdId(), ad);
        log.info(
                "Fake Bybit managed ad updated: adId={}, minRub={}, maxRub={}, rate={}",
                command.bybitAdId(),
                command.minRub(),
                command.maxRub(),
                command.rate()
        );
    }

    @Override
    public void unpublishManagedAd(String bybitAdId) {
        ensureAdId(bybitAdId);
        SimulatedAd existing = ads.get(bybitAdId);
        if (existing != null) {
            ads.put(bybitAdId, existing.unpublished(Instant.now(clock)));
        }
        log.info("Fake Bybit managed ad unpublished: adId={}", bybitAdId);
    }

    @Override
    public void sendChatMessage(String bybitOrderId, String messageUuid, String messageText) {
        SimulatedOrder order = requireOrderForGateway(bybitOrderId);
        appendMessage(order, messageUuid, messageText, SELLER_USER_ID, SELLER_ACCOUNT_ID, SELLER_NICKNAME, "merchant");
        log.info("Fake Bybit chat message sent: orderId={}, messageUuid={}", bybitOrderId, messageUuid);
    }

    @Override
    public void releaseOrder(String bybitOrderId) {
        SimulatedOrder order = requireOrderForGateway(bybitOrderId);
        SimulatedOrder released = order.withStatus(FINISHED, Instant.now(clock));
        orders.put(bybitOrderId, released);
        log.info("Fake Bybit order released: orderId={}", bybitOrderId);
    }

    public List<SimulatedAd> publishedAds() {
        return ads.values().stream()
                .filter(SimulatedAd::published)
                .sorted(Comparator.comparing(SimulatedAd::updatedAt).reversed())
                .toList();
    }

    public List<SimulatedOrder> activeSimulatorOrders() {
        return orders.values().stream()
                .filter(SimulatedOrder::active)
                .sorted(Comparator.comparing(SimulatedOrder::createdAt).reversed())
                .toList();
    }

    public Optional<SimulatedOrder> findSimulatorOrder(String bybitOrderId) {
        return Optional.ofNullable(orders.get(bybitOrderId));
    }

    public synchronized SimulatedOrder createSimulatorOrder(String bybitAdId, BigDecimal amountRub) {
        SimulatedAd ad = requirePublishedAd(bybitAdId);
        BigDecimal normalizedAmount = normalizeAmount(amountRub);
        if (normalizedAmount.compareTo(ad.minRub()) < 0 || normalizedAmount.compareTo(ad.maxRub()) > 0) {
            throw BusinessException.badRequest("Order amount must be inside fake ad range");
        }

        Instant now = Instant.now(clock);
        String orderId = "fake-" + orderSequence.incrementAndGet();
        SimulatedOrder order = new SimulatedOrder(
                orderId,
                bybitAdId,
                normalizedAmount,
                WAITING_BUYER_PAY,
                calculateQuantity(normalizedAmount, ad.rate()),
                BigDecimal.ZERO.setScale(businessProperties.getUsdtQuantityScale(), RoundingMode.UNNECESSARY),
                now,
                now,
                List.of()
        );
        orders.put(orderId, order);
        log.info("Fake Bybit order created: adId={}, orderId={}, amountRub={}", bybitAdId, orderId, normalizedAmount);
        return order;
    }

    public synchronized SimulatedOrder markSimulatorOrderPaid(String bybitOrderId) {
        SimulatedOrder order = requireOrderForSimulator(bybitOrderId);
        SimulatedOrder paid = order.withStatus(WAITING_SELLER_RELEASE, Instant.now(clock));
        orders.put(bybitOrderId, paid);
        log.info("Fake Bybit order marked as paid: orderId={}", bybitOrderId);
        return paid;
    }

    public synchronized SimulatedOrder cancelSimulatorOrder(String bybitOrderId) {
        SimulatedOrder order = requireOrderForSimulator(bybitOrderId);
        SimulatedOrder cancelled = order.withStatus(CANCELLED, Instant.now(clock));
        orders.put(bybitOrderId, cancelled);
        log.info("Fake Bybit order cancelled: orderId={}", bybitOrderId);
        return cancelled;
    }

    public synchronized SimulatedOrder sendCounterpartyMessage(String bybitOrderId, String messageText) {
        SimulatedOrder order = requireOrderForSimulator(bybitOrderId);
        return appendMessage(
                order,
                "fake-msg-" + messageSequence.incrementAndGet(),
                messageText,
                FAKE_BUYER_USER_ID,
                FAKE_BUYER_ACCOUNT_ID,
                FAKE_BUYER_NICKNAME,
                "user"
        );
    }

    public synchronized void resetSimulator() {
        orders.clear();
        ads.clear();
        orderSequence.set(1000);
        messageSequence.set(1000);
        log.info("Fake Bybit simulator state reset");
    }

    public static String statusTitle(String status) {
        return switch (status) {
            case WAITING_BUYER_PAY -> "Ожидает оплаты";
            case WAITING_SELLER_RELEASE -> "Оплачен";
            case CANCELLED -> "Отменён";
            case FINISHED -> "Завершён";
            default -> "Статус " + status;
        };
    }

    private SimulatedOrder appendMessage(
            SimulatedOrder order,
            String messageUuid,
            String messageText,
            String userId,
            String accountId,
            String nickname,
            String roleType
    ) {
        Instant now = Instant.now(clock);
        List<SimulatedChatMessage> messages = new ArrayList<>(order.messages());
        messages.add(new SimulatedChatMessage(
                "msg-" + messageSequence.incrementAndGet(),
                messageText.trim(),
                userId,
                accountId,
                now,
                messageUuid,
                nickname,
                roleType
        ));
        SimulatedOrder updated = order.withMessages(List.copyOf(messages), now);
        orders.put(order.bybitOrderId(), updated);
        return updated;
    }

    private SimulatedAd requirePublishedAd(String bybitAdId) {
        ensureAdId(bybitAdId);
        SimulatedAd ad = ads.get(bybitAdId);
        if (ad == null || !ad.published()) {
            throw BusinessException.conflict("Fake Bybit ad is not published: " + bybitAdId);
        }
        return ad;
    }

    private SimulatedOrder requireOrderForSimulator(String bybitOrderId) {
        SimulatedOrder order = orders.get(bybitOrderId);
        if (order == null) {
            throw BusinessException.conflict("Fake Bybit order not found: " + bybitOrderId);
        }
        return order;
    }

    private SimulatedOrder requireOrderForGateway(String bybitOrderId) {
        SimulatedOrder order = orders.get(bybitOrderId);
        if (order == null) {
            throw new BybitApiException("Fake Bybit order not found: " + bybitOrderId);
        }
        return order;
    }

    private BigDecimal normalizeAmount(BigDecimal amountRub) {
        if (amountRub == null || amountRub.signum() <= 0) {
            throw BusinessException.badRequest("Order amount must be positive");
        }
        return amountRub.setScale(2, RoundingMode.HALF_UP);
    }

    private void ensureAdId(String bybitAdId) {
        if (!StringUtils.hasText(bybitAdId)) {
            throw new BybitApiException("Fake Bybit managed ad id is not configured");
        }
    }

    private BigDecimal calculateQuantity(BigDecimal amountRub, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            return BigDecimal.ZERO.setScale(businessProperties.getUsdtQuantityScale(), RoundingMode.UNNECESSARY);
        }
        return amountRub.divide(rate, businessProperties.getUsdtQuantityScale(), RoundingMode.HALF_UP);
    }

    public record SimulatedAd(
            String bybitAdId,
            boolean published,
            BigDecimal rate,
            BigDecimal minRub,
            BigDecimal maxRub,
            BigDecimal quantityUsdt,
            String description,
            Instant updatedAt
    ) {

        SimulatedAd unpublished(Instant updatedAt) {
            return new SimulatedAd(
                    bybitAdId,
                    false,
                    rate,
                    minRub,
                    maxRub,
                    quantityUsdt,
                    description,
                    updatedAt
            );
        }
    }

    public record SimulatedOrder(
            String bybitOrderId,
            String bybitAdId,
            BigDecimal amountRub,
            String status,
            BigDecimal quantityUsdt,
            BigDecimal feeUsdt,
            Instant createdAt,
            Instant updatedAt,
            List<SimulatedChatMessage> messages
    ) {

        boolean active() {
            return WAITING_BUYER_PAY.equals(status) || WAITING_SELLER_RELEASE.equals(status);
        }

        BybitP2pOrder toBybitOrder() {
            return new BybitP2pOrder(
                    bybitOrderId,
                    amountRub,
                    status,
                    quantityUsdt,
                    feeUsdt
            );
        }

        SimulatedOrder withStatus(String status, Instant updatedAt) {
            return new SimulatedOrder(
                    bybitOrderId,
                    bybitAdId,
                    amountRub,
                    status,
                    quantityUsdt,
                    feeUsdt,
                    createdAt,
                    updatedAt,
                    messages
            );
        }

        SimulatedOrder withMessages(List<SimulatedChatMessage> messages, Instant updatedAt) {
            return new SimulatedOrder(
                    bybitOrderId,
                    bybitAdId,
                    amountRub,
                    status,
                    quantityUsdt,
                    feeUsdt,
                    createdAt,
                    updatedAt,
                    messages
            );
        }
    }

    public record SimulatedChatMessage(
            String id,
            String message,
            String userId,
            String accountId,
            Instant createdAt,
            String messageUuid,
            String nickname,
            String roleType
    ) {
    }
}
