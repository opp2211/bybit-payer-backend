package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;

public record WithdrawalSoundEventResponse(Long id, WithdrawalEventType eventType) {
}
