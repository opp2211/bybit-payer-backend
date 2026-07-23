package ru.maltsev.bybitpayerbackend.ai.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatSessionStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

public interface AiChatSessionRepository extends JpaRepository<AiChatSessionEntity, Long> {

    Optional<AiChatSessionEntity> findByWithdrawalRequest_Id(Long withdrawalRequestId);

    Optional<AiChatSessionEntity> findByWithdrawalRequest(WithdrawalRequestEntity withdrawal);

    List<AiChatSessionEntity> findByStatusInOrderByUpdatedAtAscIdAsc(Collection<AiChatSessionStatus> statuses);
}
