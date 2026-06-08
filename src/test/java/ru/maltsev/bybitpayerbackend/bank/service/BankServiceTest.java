package ru.maltsev.bybitpayerbackend.bank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bank.repository.BankRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;

@ExtendWith(MockitoExtension.class)
class BankServiceTest {

    @Mock
    private BankRepository bankRepository;

    @InjectMocks
    private BankService bankService;

    @Test
    void resolvesEnabledBankByTitle() {
        BankEntity bank = bank("SBERBANK", "Сбербанк");
        when(bankRepository.findByEnabledTrueOrderBySortOrderAscTitleAsc()).thenReturn(List.of(bank));

        BankEntity result = bankService.getEnabledByExternalValue("  сбербанк ");

        assertThat(result).isSameAs(bank);
    }

    @Test
    void rejectsBankMissingFromEnabledList() {
        when(bankRepository.findByEnabledTrueOrderBySortOrderAscTitleAsc()).thenReturn(List.of());

        assertThatThrownBy(() -> bankService.getEnabledByExternalValue("DISABLED"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Unsupported recipient bank: DISABLED");
    }

    private BankEntity bank(String code, String title) {
        BankEntity bank = new BankEntity();
        bank.setCode(code);
        bank.setTitle(title);
        bank.setEnabled(true);
        return bank;
    }
}
