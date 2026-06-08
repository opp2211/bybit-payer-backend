package ru.maltsev.bybitpayerbackend.receipt.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffMailReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;
import ru.maltsev.bybitpayerbackend.receipt.service.TinkoffReceiptMailService;
import ru.maltsev.bybitpayerbackend.receipt.service.TinkoffReceiptValidator;

@RestController
@Validated
@RequestMapping("/api/tinkoff-receipts")
public class TinkoffReceiptController {

    private final TinkoffReceiptValidator validator;
    private final TinkoffReceiptMailService mailService;

    public TinkoffReceiptController(TinkoffReceiptValidator validator, TinkoffReceiptMailService mailService) {
        this.validator = validator;
        this.mailService = mailService;
    }

    @PostMapping("/mail/verify")
    public List<TinkoffMailReceiptValidationResult> verifyMail(
            @Valid @RequestBody TinkoffReceiptVerificationRequest request
    ) {
        return mailService.findAndValidate(request);
    }

    @PostMapping(value = "/pdf/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TinkoffReceiptValidationResult verifyPdf(
            @RequestPart("file") MultipartFile file,
            @RequestParam @NotNull @Positive BigDecimal amount,
            @RequestParam @NotBlank String recipient,
            @RequestParam @NotBlank String phone,
            @RequestParam @NotBlank String bank
    ) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("PDF-файл не передан или пустой");
        }

        TinkoffReceiptVerificationRequest request = new TinkoffReceiptVerificationRequest(
                amount,
                recipient,
                phone,
                bank
        );
        return validator.validatePdf(file.getBytes(), request);
    }
}
