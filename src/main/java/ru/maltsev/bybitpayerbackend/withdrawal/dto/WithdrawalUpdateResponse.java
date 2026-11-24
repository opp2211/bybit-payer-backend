package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;

public record WithdrawalUpdateResponse(
        Long id,
        String withdrawalPublicId,
        WithdrawalEventType eventType,
        boolean soundEvent
) {
}
