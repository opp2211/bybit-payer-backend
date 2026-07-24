package ru.maltsev.bybitpayerbackend.bybit.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitBotChatMessageEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

public interface BybitBotChatMessageRepository extends JpaRepository<BybitBotChatMessageEntity, Long> {

    List<BybitBotChatMessageEntity> findAllByWorkspaceAndBybitOrderIdAndMsgUuidIn(
            WorkspaceEntity workspace,
            String bybitOrderId,
            Collection<String> msgUuids
    );

    List<BybitBotChatMessageEntity> findAllByBybitOrderIdAndMsgUuidIn(
            String bybitOrderId,
            Collection<String> msgUuids
    );
}
