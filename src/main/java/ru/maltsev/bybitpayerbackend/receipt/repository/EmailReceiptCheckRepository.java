package ru.maltsev.bybitpayerbackend.receipt.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.receipt.model.ReceiptVerificationStatus;

public interface EmailReceiptCheckRepository extends JpaRepository<EmailReceiptCheckEntity, Long> {

    List<EmailReceiptCheckEntity> findByWithdrawalRequest_IdOrderByCreatedAtDescIdDesc(Long withdrawalRequestId);

    Optional<EmailReceiptCheckEntity> findByIdAndWithdrawalRequest_Id(Long id, Long withdrawalRequestId);

    Optional<EmailReceiptCheckEntity> findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
            Long withdrawalRequestId,
            String bybitOrderId,
            ReceiptVerificationStatus verificationStatus
    );
}
