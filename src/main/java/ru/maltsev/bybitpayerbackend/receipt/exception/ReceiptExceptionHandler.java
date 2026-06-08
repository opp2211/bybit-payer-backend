package ru.maltsev.bybitpayerbackend.receipt.exception;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import ru.maltsev.bybitpayerbackend.receipt.dto.ReceiptApiError;

@RestControllerAdvice
public class ReceiptExceptionHandler {

    @ExceptionHandler(ReceiptMailException.class)
    public ResponseEntity<ReceiptApiError> handleMailException(ReceiptMailException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ReceiptApiError(exception.getMessage()));
    }

    @ExceptionHandler(ReceiptPdfParseException.class)
    public ResponseEntity<ReceiptApiError> handlePdfException(ReceiptPdfParseException exception) {
        return ResponseEntity.badRequest()
                .body(new ReceiptApiError(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ReceiptApiError> handleValidationException(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        return ResponseEntity.badRequest()
                .body(new ReceiptApiError("Некорректный запрос", details));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ReceiptApiError> handleMethodValidationException(HandlerMethodValidationException exception) {
        List<String> details = exception.getAllErrors().stream()
                .map(error -> error.getDefaultMessage() == null ? error.toString() : error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ReceiptApiError("Некорректный запрос", details));
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    public ResponseEntity<ReceiptApiError> handleBadRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ReceiptApiError(exception.getMessage()));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
