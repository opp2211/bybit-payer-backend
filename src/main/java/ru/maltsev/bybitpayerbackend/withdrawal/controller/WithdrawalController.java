package ru.maltsev.bybitpayerbackend.withdrawal.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.withdrawal.dto.CreateWithdrawalRequest;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalDetailsResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalService;

@RestController
@RequestMapping("/api/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse create(@Valid @RequestBody CreateWithdrawalRequest request) {
        return withdrawalService.create(request);
    }

    @GetMapping("/active")
    public List<WithdrawalResponse> getActive() {
        return withdrawalService.getActive();
    }

    @GetMapping("/completed")
    public List<WithdrawalResponse> getCompleted() {
        return withdrawalService.getCompleted();
    }

    @GetMapping("/{id}")
    public WithdrawalDetailsResponse getDetails(@PathVariable Long id) {
        return withdrawalService.getDetails(id);
    }

    @DeleteMapping("/{id}")
    public WithdrawalResponse cancel(@PathVariable Long id) {
        return withdrawalService.cancel(id);
    }

    @PostMapping("/{id}/mark-seen")
    public WithdrawalResponse markSeen(@PathVariable Long id) {
        return withdrawalService.markCompletedSeen(id);
    }
}
