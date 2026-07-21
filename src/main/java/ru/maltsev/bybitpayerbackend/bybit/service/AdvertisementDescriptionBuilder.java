package ru.maltsev.bybitpayerbackend.bybit.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalPaymentRules;

@Component
public class AdvertisementDescriptionBuilder {

    public static final int MAX_DESCRIPTION_LENGTH = 1000;

    private static final String SENDER_FIRST_PARTY_REQUIREMENT =
            "Работаю только с 1 лицами "
                    + "(Имя Ф. отправителя должны совпадать с верифицированным именем на Bybit)";
    private static final String AD_DESCRIPTION_TEMPLATE =
            "%s ___ Заходите только на сумму %s руб.  " +
                    "- другие суммы - отмена! ___ %s";

    public String build(
            PayerBankType payerBankType,
            WithdrawalMethod withdrawalMethod,
            boolean thirdPartyTransfer,
            boolean recipientCardTbank,
            boolean requireSenderFirstParty,
            List<BigDecimal> amounts
    ) {
        String description = buildUnchecked(
                payerBankType,
                withdrawalMethod,
                thirdPartyTransfer,
                recipientCardTbank,
                requireSenderFirstParty,
                amounts
        );
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw BusinessException.conflict(
                    "Описание объявления превышает лимит Bybit",
                    List.of(
                            "descriptionLength=" + description.length(),
                            "maxDescriptionLength=" + MAX_DESCRIPTION_LENGTH
                    )
            );
        }
        return description;
    }

    public boolean fits(
            PayerBankType payerBankType,
            WithdrawalMethod withdrawalMethod,
            boolean thirdPartyTransfer,
            boolean recipientCardTbank,
            boolean requireSenderFirstParty,
            List<BigDecimal> amounts
    ) {
        return buildUnchecked(
                payerBankType,
                withdrawalMethod,
                thirdPartyTransfer,
                recipientCardTbank,
                requireSenderFirstParty,
                amounts
        ).length() <= MAX_DESCRIPTION_LENGTH;
    }

    private String buildUnchecked(
            PayerBankType payerBankType,
            WithdrawalMethod withdrawalMethod,
            boolean thirdPartyTransfer,
            boolean recipientCardTbank,
            boolean requireSenderFirstParty,
            List<BigDecimal> amounts
    ) {
        PayerBankType effectivePayerBankType = PayerBankType.effective(payerBankType);
        WithdrawalMethod effectiveWithdrawalMethod = WithdrawalMethod.effective(withdrawalMethod);
        String amountText = amounts.stream()
                .sorted(Comparator.naturalOrder())
                .map(this::formatRubAmount)
                .collect(Collectors.joining(" / "));
        String advertisementTail = WithdrawalPaymentRules.advertisementTail(
                effectivePayerBankType,
                effectiveWithdrawalMethod,
                thirdPartyTransfer,
                recipientCardTbank
        );

        return AD_DESCRIPTION_TEMPLATE.formatted(
                advertisementIntro(effectivePayerBankType, requireSenderFirstParty),
                amountText,
                advertisementTail
        );
    }

    private String advertisementIntro(PayerBankType payerBankType, boolean requireSenderFirstParty) {
        String intro = PayerBankType.effective(payerBankType).getAdvertisementIntro();
        return requireSenderFirstParty
                ? SENDER_FIRST_PARTY_REQUIREMENT + " ___ " + intro
                : intro;
    }

    private String formatRubAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
