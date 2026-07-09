package ru.maltsev.bybitpayerbackend.withdrawal.controller;

import java.util.List;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.withdrawal.dto.CreateWithdrawalRequest;
import ru.maltsev.bybitpayerbackend.bybit.dto.SendChatMessageRequest;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalDetailsResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalService;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;

@RestController
@RequestMapping("/api/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;
    private final BybitChatService chatService;

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

    @PostMapping("/{id}/release")
    public WithdrawalResponse release(@PathVariable Long id) {
        return withdrawalService.release(id);
    }

    @PostMapping("/{id}/chat/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendChatMessage(
            @PathVariable Long id,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        chatService.sendMessage(id, request.message());
    }

    @GetMapping("/{id}/receipts/{receiptId}/pdf")
    public ResponseEntity<byte[]> getReceiptPdf(
            @PathVariable Long id,
            @PathVariable Long receiptId
    ) {
        EmailReceiptCheckEntity receipt = withdrawalService.getReceiptPdf(id, receiptId);
        String filename = receipt.getPdfFilename() == null ? "receipt.pdf" : receipt.getPdfFilename();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(receipt.getPdfContent());
    }
}
