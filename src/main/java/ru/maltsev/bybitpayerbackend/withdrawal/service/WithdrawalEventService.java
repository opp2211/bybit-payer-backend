package ru.maltsev.bybitpayerbackend.withdrawal.service;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalUpdateResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalEventEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalActorType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalEventRepository;

@Service
@RequiredArgsConstructor
public class WithdrawalEventService {

    private final WithdrawalEventRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public void add(WithdrawalRequestEntity withdrawal, WithdrawalEventType eventType, String message) {
        add(withdrawal, eventType, message, (String) null);
    }

    public void add(WithdrawalRequestEntity withdrawal, WithdrawalEventType eventType, String message, String payloadJson) {
        add(withdrawal, eventType, message, payloadJson, null);
    }

    public void add(
            WithdrawalRequestEntity withdrawal,
            WithdrawalEventType eventType,
            String message,
            UserEntity actor
    ) {
        add(withdrawal, eventType, message, null, actor);
    }

    public void add(
            WithdrawalRequestEntity withdrawal,
            WithdrawalEventType eventType,
            String message,
            String payloadJson,
            UserEntity actor
    ) {
        WithdrawalEventEntity event = new WithdrawalEventEntity();
        event.setWithdrawalRequest(withdrawal);
        event.setEventType(eventType);
        event.setMessage(message);
        event.setPayloadJson(payloadJson);
        event.setActor(actor);
        event.setActorType(actor == null ? WithdrawalActorType.SYSTEM : WithdrawalActorType.USER);
        event.setCreatedAt(Instant.now(clock));
        WithdrawalEventEntity saved = repository.save(event);
        if (withdrawal.getWorkspace() != null && withdrawal.getPublicId() != null
                && eventType != WithdrawalEventType.CHAT_MESSAGE_SENT) {
            publisher.publishEvent(new WithdrawalUpdatesStream.CommittedUpdate(
                    withdrawal.getWorkspace().getId(),
                    new WithdrawalUpdateResponse(
                            saved.getId(),
                            withdrawal.getPublicId(),
                            eventType,
                            WithdrawalEventType.SOUND_EVENTS.contains(eventType)
                    )
            ));
        }
    }
}
