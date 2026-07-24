package ru.maltsev.bybitpayerbackend.bybit.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "bybit_bot_chat_messages",
        indexes = {
                @Index(name = "idx_bybit_bot_chat_messages_workspace_order", columnList = "workspace_id, bybit_order_id"),
                @Index(name = "idx_bybit_bot_chat_messages_withdrawal", columnList = "withdrawal_request_id")
        }
)
public class BybitBotChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id")
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "withdrawal_request_id")
    private WithdrawalRequestEntity withdrawalRequest;

    @Column(name = "bybit_order_id", nullable = false, length = 128)
    private String bybitOrderId;

    @Column(name = "msg_uuid", nullable = false, length = 64, unique = true)
    private String msgUuid;

    @Column(name = "message_text")
    private String messageText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
