package ru.maltsev.bybitpayerbackend.bank.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.bank.entity.BankAliasEntity;
import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;

public interface BankAliasRepository extends JpaRepository<BankAliasEntity, Long> {

    List<BankAliasEntity> findByBankOrderByAliasAsc(BankEntity bank);

    List<BankAliasEntity> findByBank_IdOrderByAliasAsc(Long bankId);

    Optional<BankAliasEntity> findByAliasNormalized(String aliasNormalized);

    void deleteByBank(BankEntity bank);
}
