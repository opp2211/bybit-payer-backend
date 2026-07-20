package ru.maltsev.bybitpayerbackend.receipt.service;

public record ReceiptMailbox(
        String host,
        int port,
        String username,
        String password
) {

    public boolean ssl() {
        return port == 993;
    }

    public String protocol() {
        return ssl() ? "imaps" : "imap";
    }
}
