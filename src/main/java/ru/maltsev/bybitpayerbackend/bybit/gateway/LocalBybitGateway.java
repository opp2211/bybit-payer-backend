package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;

@Component
@ConditionalOnProperty(prefix = "bybit", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalBybitGateway implements BybitGateway {

    private final BybitProperties properties;

    public LocalBybitGateway(BybitProperties properties) {
        this.properties = properties;
    }

    @Override
    public BybitReadiness checkReadiness() {
        return new BybitReadiness(
                true,
                "LOCAL_NOOP",
                "Bybit integration is disabled",
                fetchAvailableUsdtBalance()
        );
    }

    @Override
    public BigDecimal fetchReferenceRate() {
        return properties.getLocal().getReferenceRate();
    }

    @Override
    public BigDecimal fetchReferenceRate(int adIndex) {
        return fetchReferenceRate();
    }

    @Override
    public BigDecimal fetchAvailableUsdtBalance() {
        return properties.getLocal().getAvailableUsdt();
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
    public void updateManagedAd(AdUpdateCommand command) {
    }

    @Override
    public void unpublishManagedAd(String bybitAdId) {
    }

    @Override
    public void sendChatMessage(String bybitOrderId, int messageIndex, String messageText) {
    }

    @Override
    public void releaseOrder(String bybitOrderId) {
    }
}
