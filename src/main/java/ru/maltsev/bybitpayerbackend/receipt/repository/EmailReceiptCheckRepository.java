package ru.maltsev.bybitpayerbackend.receipt.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;

public interface EmailReceiptCheckRepository extends JpaRepository<EmailReceiptCheckEntity, Long> {

    List<EmailReceiptCheckEntity> findByWithdrawalRequest_IdOrderByCreatedAtDescIdDesc(Long withdrawalRequestId);
}
