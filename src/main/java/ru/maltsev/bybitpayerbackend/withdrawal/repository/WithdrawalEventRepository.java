package ru.maltsev.bybitpayerbackend.withdrawal.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalEventEntity;

public interface WithdrawalEventRepository extends JpaRepository<WithdrawalEventEntity, Long> {

    List<WithdrawalEventEntity> findByWithdrawalRequest_IdOrderByCreatedAtAscIdAsc(Long withdrawalRequestId);
}
