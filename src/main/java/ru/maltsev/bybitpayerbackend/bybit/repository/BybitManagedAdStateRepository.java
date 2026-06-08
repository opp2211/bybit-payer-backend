package ru.maltsev.bybitpayerbackend.bybit.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;

public interface BybitManagedAdStateRepository extends JpaRepository<BybitManagedAdStateEntity, Long> {
}
