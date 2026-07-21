package ru.maltsev.bybitpayerbackend.admin.controller;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.bank.admin.AdminBankRequest;
import ru.maltsev.bybitpayerbackend.bank.admin.AdminBankResponse;
import ru.maltsev.bybitpayerbackend.bank.service.BankService;

@RestController
@RequestMapping("/api/admin/banks")
@RequiredArgsConstructor
public class AdminBankController {

    private final BankService bankService;

    @GetMapping
    public List<AdminBankResponse> list() {
        return bankService.getAdminBanks();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminBankResponse create(@Valid @RequestBody AdminBankRequest request) {
        return bankService.createBank(request);
    }

    @PutMapping("/{id}")
    public AdminBankResponse update(@PathVariable Long id, @Valid @RequestBody AdminBankRequest request) {
        return bankService.updateBank(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        bankService.deleteBank(id);
    }
}
