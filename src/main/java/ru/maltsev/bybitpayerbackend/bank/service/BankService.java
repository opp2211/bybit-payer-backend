package ru.maltsev.bybitpayerbackend.bank.service;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bank.admin.AdminBankRequest;
import ru.maltsev.bybitpayerbackend.bank.admin.AdminBankResponse;
import ru.maltsev.bybitpayerbackend.bank.dto.BankResponse;
import ru.maltsev.bybitpayerbackend.bank.entity.BankAliasEntity;
import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bank.repository.BankAliasRepository;
import ru.maltsev.bybitpayerbackend.bank.repository.BankRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;

@Service
@RequiredArgsConstructor
public class BankService {

    private final BankRepository bankRepository;
    private final BankAliasRepository bankAliasRepository;

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
                        || normalize(bank.getTitle()).equals(normalizedValue)
                        || bankAliasRepository.findByBank_IdOrderByAliasAsc(bank.getId()).stream()
                        .anyMatch(alias -> alias.getAliasNormalized().equals(normalizedValue)))
                .findFirst()
                .orElseThrow(() -> BusinessException.badRequest("Unsupported recipient bank: " + value));
    }

    @Transactional(readOnly = true)
    public List<AdminBankResponse> getAdminBanks() {
        return bankRepository.findAllByOrderBySortOrderAscTitleAsc().stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @Transactional
    public AdminBankResponse createBank(AdminBankRequest request) {
        validateUniqueCodeAndTitle(null, request.code(), request.title());
        BankEntity bank = new BankEntity();
        apply(bank, request);
        bank = bankRepository.save(bank);
        replaceAliases(bank, request.aliases());
        return toAdminResponse(bank);
    }

    @Transactional
    public AdminBankResponse updateBank(Long id, AdminBankRequest request) {
        BankEntity bank = bankRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Bank not found: " + id));
        validateUniqueCodeAndTitle(id, request.code(), request.title());
        apply(bank, request);
        bank = bankRepository.save(bank);
        replaceAliases(bank, request.aliases());
        return toAdminResponse(bank);
    }

    @Transactional
    public void deleteBank(Long id) {
        BankEntity bank = bankRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Bank not found: " + id));
        try {
            bankRepository.delete(bank);
            bankRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("Bank is used by existing withdrawals and cannot be deleted");
        }
    }

    private void apply(BankEntity bank, AdminBankRequest request) {
        bank.setCode(request.code().trim().toUpperCase(Locale.ROOT));
        bank.setTitle(request.title().trim());
        bank.setEnabled(request.enabled());
        bank.setSortOrder(request.sortOrder());
    }

    private void validateUniqueCodeAndTitle(Long currentId, String code, String title) {
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        bankRepository.findByCode(normalizedCode)
                .filter(bank -> currentId == null || !bank.getId().equals(currentId))
                .ifPresent(bank -> {
                    throw BusinessException.conflict("Bank code is already used");
                });
        bankRepository.findByTitle(title.trim())
                .filter(bank -> currentId == null || !bank.getId().equals(currentId))
                .ifPresent(bank -> {
                    throw BusinessException.conflict("Bank title is already used");
                });
    }

    private void replaceAliases(BankEntity bank, List<String> rawAliases) {
        bankAliasRepository.deleteByBank(bank);
        List<BankAliasEntity> aliases = new ArrayList<>();
        for (String alias : normalizedAliases(rawAliases)) {
            String normalized = normalize(alias);
            bankAliasRepository.findByAliasNormalized(normalized)
                    .ifPresent(existing -> {
                        throw BusinessException.conflict("Bank alias is already used: " + alias);
                    });
            BankAliasEntity entity = new BankAliasEntity();
            entity.setBank(bank);
            entity.setAlias(alias);
            entity.setAliasNormalized(normalized);
            aliases.add(entity);
        }
        bankAliasRepository.saveAll(aliases);
    }

    private List<String> normalizedAliases(List<String> rawAliases) {
        if (rawAliases == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        rawAliases.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(result::add);
        return List.copyOf(result);
    }

    private AdminBankResponse toAdminResponse(BankEntity bank) {
        return new AdminBankResponse(
                bank.getId(),
                bank.getCode(),
                bank.getTitle(),
                bank.isEnabled(),
                bank.getSortOrder(),
                bankAliasRepository.findByBankOrderByAliasAsc(bank).stream()
                        .map(BankAliasEntity::getAlias)
                        .toList()
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
