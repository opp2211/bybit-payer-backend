package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@Slf4j
public class FakeBybitGateway implements BybitGateway {

    private static final BigDecimal REFERENCE_RATE = new BigDecimal("100.00");
    private static final BigDecimal AVAILABLE_USDT_BALANCE = new BigDecimal("100000.00");

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
        return List.of();
    }

    @Override
    public Optional<BybitP2pOrder> fetchOrder(String bybitOrderId) {
        return Optional.empty();
    }

    @Override
    public List<BybitChatMessage> fetchChatMessages(String bybitOrderId) {
        return List.of();
    }

    @Override
    public void updateManagedAd(AdUpdateCommand command) {
        log.info("Fake Bybit gateway skipped managed ad update: adId={}, published={}", command.bybitAdId(), command.published());
    }

    @Override
    public void unpublishManagedAd(String bybitAdId) {
        log.info("Fake Bybit gateway skipped managed ad unpublish: adId={}", bybitAdId);
    }

    @Override
    public void sendChatMessage(String bybitOrderId, String messageUuid, String messageText) {
        log.info("Fake Bybit gateway skipped chat message send: orderId={}, messageUuid={}", bybitOrderId, messageUuid);
    }

    @Override
    public void releaseOrder(String bybitOrderId) {
        log.info("Fake Bybit gateway skipped order release: orderId={}", bybitOrderId);
    }
}
