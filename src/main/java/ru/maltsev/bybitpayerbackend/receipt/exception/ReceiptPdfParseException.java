package ru.maltsev.bybitpayerbackend.receipt.exception;

public class ReceiptPdfParseException extends RuntimeException {

    public ReceiptPdfParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
