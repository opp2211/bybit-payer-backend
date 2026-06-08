package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;
import java.util.List;

public interface BybitGateway {

    BybitReadiness checkReadiness();

    BigDecimal fetchReferenceRate();

    BigDecimal fetchAvailableUsdtBalance();

    List<BybitP2pOrder> fetchActiveOrders();

    void updateManagedAd(AdUpdateCommand command);

    void unpublishManagedAd(String bybitAdId);

    void sendChatMessage(String bybitOrderId, int messageIndex, String messageText);

    void requestCancel(String bybitOrderId);

    void releaseOrder(String bybitOrderId);
}
