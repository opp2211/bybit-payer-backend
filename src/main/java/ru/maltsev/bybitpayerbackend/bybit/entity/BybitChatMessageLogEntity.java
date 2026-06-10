package ru.maltsev.bybitpayerbackend.bybit.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.maltsev.bybitpayerbackend.bybit.model.ChatMessageStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bybit_chat_message_logs")
public class BybitChatMessageLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bybit_order_id", nullable = false, length = 128)
    private String bybitOrderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "withdrawal_request_id", nullable = false)
    private WithdrawalRequestEntity withdrawalRequest;

    @Column(name = "message_index", nullable = false)
    private int messageIndex;

    @Column(name = "message_text", nullable = false)
    private String messageText;

    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 48)
    private ChatMessageStatus status;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "error")
    private String error;
}
