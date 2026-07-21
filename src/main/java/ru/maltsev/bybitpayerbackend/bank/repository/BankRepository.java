package ru.maltsev.bybitpayerbackend.bank.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;

public interface BankRepository extends JpaRepository<BankEntity, Long> {

    List<BankEntity> findByEnabledTrueOrderBySortOrderAscTitleAsc();

    List<BankEntity> findAllByOrderBySortOrderAscTitleAsc();

    Optional<BankEntity> findByCode(String code);

    Optional<BankEntity> findByTitle(String title);
}
