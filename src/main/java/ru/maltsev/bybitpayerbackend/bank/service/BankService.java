package ru.maltsev.bybitpayerbackend.bank.service;

import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bank.dto.BankResponse;
import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bank.repository.BankRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;

@Service
@RequiredArgsConstructor
public class BankService {

    private final BankRepository bankRepository;

    @Transactional(readOnly = true)
    public List<BankResponse> getEnabledBanks() {
        return bankRepository.findByEnabledTrueOrderBySortOrderAscTitleAsc().stream()
                .map(bank -> new BankResponse(bank.getCode(), bank.getTitle()))
                .toList();
    }

    @Transactional(readOnly = true)
    public BankEntity getEnabledByExternalValue(String value) {
        String normalizedValue = normalize(value);
        return bankRepository.findByEnabledTrueOrderBySortOrderAscTitleAsc().stream()
                .filter(bank -> normalize(bank.getCode()).equals(normalizedValue)
                        || normalize(bank.getTitle()).equals(normalizedValue))
                .findFirst()
                .orElseThrow(() -> BusinessException.badRequest("Unsupported recipient bank: " + value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
