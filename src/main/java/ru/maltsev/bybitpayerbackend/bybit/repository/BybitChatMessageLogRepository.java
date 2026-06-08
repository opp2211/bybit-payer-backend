package ru.maltsev.bybitpayerbackend.bybit.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitChatMessageLogEntity;

public interface BybitChatMessageLogRepository extends JpaRepository<BybitChatMessageLogEntity, Long> {

    Optional<BybitChatMessageLogEntity> findByBybitOrderIdAndMessageIndex(String bybitOrderId, int messageIndex);

    List<BybitChatMessageLogEntity> findByWithdrawalRequest_IdOrderByMessageIndexAsc(Long withdrawalRequestId);
}
