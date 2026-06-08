package ru.maltsev.bybitpayerbackend.bank.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.bank.dto.BankResponse;
import ru.maltsev.bybitpayerbackend.bank.service.BankService;

@RestController
@RequestMapping("/api/banks")
public class BankController {

    private final BankService bankService;

    public BankController(BankService bankService) {
        this.bankService = bankService;
    }

    @GetMapping
    public List<BankResponse> getBanks() {
        return bankService.getEnabledBanks();
    }
}
