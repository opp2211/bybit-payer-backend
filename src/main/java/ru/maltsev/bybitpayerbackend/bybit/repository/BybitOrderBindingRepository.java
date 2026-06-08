package ru.maltsev.bybitpayerbackend.bybit.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;

public interface BybitOrderBindingRepository extends JpaRepository<BybitOrderBindingEntity, Long> {

    Optional<BybitOrderBindingEntity> findByBybitOrderId(String bybitOrderId);

    Optional<BybitOrderBindingEntity> findByWithdrawalRequest_IdAndStatus(Long withdrawalRequestId, OrderBindingStatus status);
}
