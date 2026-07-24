package ru.maltsev.bybitpayerbackend.ai.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiModelCallPurpose;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_chat_model_calls")
public class AiChatModelCallEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ai_chat_session_id", nullable = false)
    private AiChatSessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "withdrawal_request_id", nullable = false)
    private WithdrawalRequestEntity withdrawalRequest;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private AiModelCallPurpose purpose;

    @Column(name = "prompt_json", nullable = false, columnDefinition = "text")
    private String promptJson;

    @Column(name = "response_json", columnDefinition = "text")
    private String responseJson;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
