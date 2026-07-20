package ru.maltsev.bybitpayerbackend.bank.admin;

import java.util.List;

public record AdminBankResponse(
        Long id,
        String code,
        String title,
        boolean enabled,
        int sortOrder,
        List<String> aliases
) {
}
