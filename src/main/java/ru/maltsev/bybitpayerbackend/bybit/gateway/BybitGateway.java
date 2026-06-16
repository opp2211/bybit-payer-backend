package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface BybitGateway {

    BybitReadiness checkReadiness();

    BigDecimal fetchReferenceRate();

    BigDecimal fetchReferenceRate(int adIndex);

    BigDecimal fetchAvailableUsdtBalance();

    List<BybitP2pOrder> fetchActiveOrders();

    Optional<BybitP2pOrder> fetchOrder(String bybitOrderId);

    List<BybitChatMessage> fetchChatMessages(String bybitOrderId);

    void updateManagedAd(AdUpdateCommand command);

    void unpublishManagedAd(String bybitAdId);

    void sendChatMessage(String bybitOrderId, String messageUuid, String messageText);

    void releaseOrder(String bybitOrderId);

    void cancelOrder(String bybitOrderId);
}
