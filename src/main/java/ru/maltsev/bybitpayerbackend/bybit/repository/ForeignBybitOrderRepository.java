package ru.maltsev.bybitpayerbackend.bybit.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bybit.entity.ForeignBybitOrderEntity;

public interface ForeignBybitOrderRepository extends JpaRepository<ForeignBybitOrderEntity, Long> {

    Optional<ForeignBybitOrderEntity> findByBybitOrderId(String bybitOrderId);

    List<ForeignBybitOrderEntity> findAllByOrderByUpdatedAtDescIdDesc();

    List<ForeignBybitOrderEntity> findByAttentionRequiredTrueOrderByUpdatedAtDescIdDesc();
}
