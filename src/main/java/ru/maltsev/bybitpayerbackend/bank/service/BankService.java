package ru.maltsev.bybitpayerbackend.bank.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bank.dto.BankResponse;
import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bank.repository.BankRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;

@Service
public class BankService {

    private final BankRepository bankRepository;

    public BankService(BankRepository bankRepository) {
        this.bankRepository = bankRepository;
    }

    @Transactional(readOnly = true)
    public List<BankResponse> getEnabledBanks() {
        return bankRepository.findByEnabledTrueOrderBySortOrderAscTitleAsc().stream()
                .map(bank -> new BankResponse(bank.getCode(), bank.getTitle()))
                .toList();
    }

    @Transactional(readOnly = true)
    public BankEntity getEnabledByExternalValue(String value) {
        return bankRepository.findByEnabledTrueOrderBySortOrderAscTitleAsc().stream()
                .filter(bank -> bank.getCode().equals(value))
                .findFirst()
                .orElseThrow(() -> BusinessException.badRequest("Unsupported recipient bank: " + value));
    }
}
