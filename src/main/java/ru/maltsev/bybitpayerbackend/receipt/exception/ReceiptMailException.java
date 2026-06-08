package ru.maltsev.bybitpayerbackend.receipt.exception;

public class ReceiptMailException extends RuntimeException {

    public ReceiptMailException(String message) {
        super(message);
    }

    public ReceiptMailException(String message, Throwable cause) {
        super(message, cause);
    }
}
