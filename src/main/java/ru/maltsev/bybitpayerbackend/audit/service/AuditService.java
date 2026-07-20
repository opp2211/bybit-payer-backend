package ru.maltsev.bybitpayerbackend.audit.service;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.audit.entity.AuditEventEntity;
import ru.maltsev.bybitpayerbackend.audit.repository.AuditEventRepository;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;
    private final Clock clock;

    public void add(
            UserEntity actor,
            WorkspaceEntity workspace,
            String action,
            String subjectType,
            String subjectPublicId,
            String payloadJson
    ) {
        AuditEventEntity event = new AuditEventEntity();
        event.setActor(actor);
        event.setWorkspace(workspace);
        event.setAction(action);
        event.setSubjectType(subjectType);
        event.setSubjectPublicId(subjectPublicId);
        event.setPayloadJson(payloadJson);
        event.setCreatedAt(Instant.now(clock));
        repository.save(event);
    }
}
