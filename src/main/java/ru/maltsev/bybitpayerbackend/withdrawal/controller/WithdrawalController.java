package ru.maltsev.bybitpayerbackend.withdrawal.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ru.maltsev.bybitpayerbackend.bybit.dto.SendChatMessageRequest;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.CreateWithdrawalRequest;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalAdvertisementPreviewResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalDetailsResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalSoundEventResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalUpdateResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalService;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalUpdatesStream;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;

@RestController
@RequestMapping("/api/workspaces/{workspacePublicId}/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;
    private final BybitChatService chatService;
    private final WithdrawalUpdatesStream updatesStream;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse create(
            @PathVariable String workspacePublicId,
            @Valid @RequestBody CreateWithdrawalRequest request
    ) {
        return withdrawalService.create(workspacePublicId, request);
    }

    @PostMapping("/preview")
    public WithdrawalAdvertisementPreviewResponse previewAdvertisement(
            @PathVariable String workspacePublicId,
            @Valid @RequestBody CreateWithdrawalRequest request
    ) {
        return withdrawalService.previewAdvertisement(workspacePublicId, request);
    }

    @GetMapping("/active")
    public List<WithdrawalResponse> getActive(@PathVariable String workspacePublicId) {
        return withdrawalService.getActive(workspacePublicId);
    }

    @GetMapping("/completed")
    public List<WithdrawalResponse> getCompleted(@PathVariable String workspacePublicId) {
        return withdrawalService.getCompleted(workspacePublicId);
    }

    @GetMapping("/sound-events")
    public List<WithdrawalSoundEventResponse> getRecentSoundEvents(@PathVariable String workspacePublicId) {
        return withdrawalService.getRecentSoundEvents(workspacePublicId);
    }

    @GetMapping(value = "/updates", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream committed withdrawal updates")
    @ApiResponse(responseCode = "200",
            description = "SSE events: ready, withdrawal, heartbeat, and chat for the requested withdrawal",
            content = @Content(mediaType = "text/event-stream",
                    schema = @Schema(implementation = WithdrawalUpdateResponse.class)))
    public ResponseEntity<SseEmitter> streamUpdates(
            @PathVariable String workspacePublicId,
            @RequestParam(name = "chat", required = false) String chatPublicId,
            HttpSession session
    ) throws IOException {
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
                    .header("X-Accel-Buffering", "no")
                    .body(updatesStream.subscribe(workspacePublicId, chatPublicId, session));
        } catch (BusinessException exception) {
            return ResponseEntity.status(exception.getStatus()).build();
        } catch (EntityNotFoundException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{withdrawalPublicId}")
    public WithdrawalDetailsResponse getDetails(
            @PathVariable String workspacePublicId,
            @PathVariable String withdrawalPublicId
    ) {
        return withdrawalService.getDetails(workspacePublicId, withdrawalPublicId);
    }

    @DeleteMapping("/{withdrawalPublicId}")
    public WithdrawalResponse cancel(
            @PathVariable String workspacePublicId,
            @PathVariable String withdrawalPublicId
    ) {
        return withdrawalService.cancel(workspacePublicId, withdrawalPublicId);
    }

    @PostMapping("/{withdrawalPublicId}/mark-seen")
    public WithdrawalResponse markSeen(
            @PathVariable String workspacePublicId,
            @PathVariable String withdrawalPublicId
    ) {
        return withdrawalService.markCompletedSeen(workspacePublicId, withdrawalPublicId);
    }

    @PostMapping("/{withdrawalPublicId}/release")
    public WithdrawalResponse release(
            @PathVariable String workspacePublicId,
            @PathVariable String withdrawalPublicId
    ) {
        return withdrawalService.release(workspacePublicId, withdrawalPublicId);
    }

    @PostMapping("/{withdrawalPublicId}/chat/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendChatMessage(
            @PathVariable String workspacePublicId,
            @PathVariable String withdrawalPublicId,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        chatService.sendMessage(workspacePublicId, withdrawalPublicId, request.message());
    }

    @GetMapping("/{withdrawalPublicId}/receipts/{receiptId}/pdf")
    public ResponseEntity<byte[]> getReceiptPdf(
            @PathVariable String workspacePublicId,
            @PathVariable String withdrawalPublicId,
            @PathVariable Long receiptId
    ) {
        EmailReceiptCheckEntity receipt = withdrawalService.getReceiptPdf(workspacePublicId, withdrawalPublicId, receiptId);
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
