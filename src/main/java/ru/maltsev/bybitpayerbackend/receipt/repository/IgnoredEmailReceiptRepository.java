package ru.maltsev.bybitpayerbackend.receipt.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ru.maltsev.bybitpayerbackend.receipt.entity.IgnoredEmailReceiptEntity;

public interface IgnoredEmailReceiptRepository extends JpaRepository<IgnoredEmailReceiptEntity, Long> {

    @Query("""
            select ignored.receiptKey
            from IgnoredEmailReceiptEntity ignored
            where ignored.withdrawalRequest.id = :withdrawalRequestId
            """)
    Set<String> findReceiptKeysByWithdrawalRequestId(@Param("withdrawalRequestId") Long withdrawalRequestId);

    boolean existsByWithdrawalRequest_IdAndReceiptKey(Long withdrawalRequestId, String receiptKey);
}
