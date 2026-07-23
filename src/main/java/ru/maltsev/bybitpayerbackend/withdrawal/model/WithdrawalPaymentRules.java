package ru.maltsev.bybitpayerbackend.withdrawal.model;

import java.util.ArrayList;
import java.util.List;

import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;

public final class WithdrawalPaymentRules {

    private WithdrawalPaymentRules() {
    }

    public static void validateMethod(PayerBankType payerBankType, WithdrawalMethod withdrawalMethod) {
        if (isMethodAllowed(payerBankType, withdrawalMethod)) {
            return;
        }
        throw BusinessException.badRequest(
                "Withdrawal method " + withdrawalMethod + " is not allowed for payer bank " + payerBankType
        );
    }

    public static boolean isMethodAllowed(PayerBankType payerBankType, WithdrawalMethod withdrawalMethod) {
        PayerBankType effectivePayerBankType = PayerBankType.effective(payerBankType);
        WithdrawalMethod effectiveWithdrawalMethod = WithdrawalMethod.effective(withdrawalMethod);
        return switch (effectivePayerBankType) {
            case TBANK_AUTO -> effectiveWithdrawalMethod == WithdrawalMethod.SBP
                    || effectiveWithdrawalMethod == WithdrawalMethod.CARD_NUMBER;
            case SBERBANK -> effectiveWithdrawalMethod == WithdrawalMethod.ACCOUNT_NUMBER;
            case ANY_BANK -> effectiveWithdrawalMethod == WithdrawalMethod.SBP;
        };
    }

    public static boolean isAutoReleaseEnabled(PayerBankType payerBankType, WithdrawalMethod withdrawalMethod) {
        PayerBankType effectivePayerBankType = PayerBankType.effective(payerBankType);
        WithdrawalMethod effectiveWithdrawalMethod = WithdrawalMethod.effective(withdrawalMethod);
        return effectivePayerBankType == PayerBankType.TBANK_AUTO
                && (effectiveWithdrawalMethod == WithdrawalMethod.SBP
                || effectiveWithdrawalMethod == WithdrawalMethod.CARD_NUMBER);
    }

    public static String queueGroupKey(
            PayerBankType payerBankType,
            WithdrawalMethod withdrawalMethod,
            boolean thirdPartyTransfer,
            boolean recipientCardTbank,
            boolean requireSenderFirstParty
    ) {
        PayerBankType effectivePayerBankType = PayerBankType.effective(payerBankType);
        WithdrawalMethod effectiveWithdrawalMethod = WithdrawalMethod.effective(withdrawalMethod);
        List<String> parts = new ArrayList<>();
        parts.add(effectivePayerBankType.name());
        parts.add(effectiveWithdrawalMethod.name());
        parts.add(requireSenderFirstParty ? "SENDER_FIRST_PARTY" : "ANY_SENDER");
        parts.add(thirdPartyTransfer ? "THIRD_PARTY" : "PERSONAL");
        if (effectiveWithdrawalMethod == WithdrawalMethod.CARD_NUMBER) {
            parts.add(recipientCardTbank ? "TBANK_CARD" : "OTHER_CARD");
        }
        return String.join(":", parts);
    }

    public static String rangeQueueGroupKey(String publicId) {
        return "RANGE:" + publicId;
    }

    public static String advertisementTail(
            PayerBankType payerBankType,
            WithdrawalMethod withdrawalMethod,
            boolean thirdPartyTransfer,
            boolean recipientCardTbank
    ) {
        return switch (WithdrawalMethod.effective(withdrawalMethod)) {
            case SBP -> thirdPartyTransfer
                    ? "Принимаю на карту 3 лица по СБП"
                    : "Принимаю на личную карту / карту жены по СБП";
            case CARD_NUMBER -> cardNumberAdvertisementTail(thirdPartyTransfer, recipientCardTbank);
            case ACCOUNT_NUMBER -> thirdPartyTransfer
                    ? "Принимаю СБЕР-СБЕР по номеру счета на 3 лицо"
                    : "Принимаю СБЕР-СБЕР по номеру счета на личную карту / карту жены";
        };
    }

    private static String cardNumberAdvertisementTail(boolean thirdPartyTransfer, boolean recipientCardTbank) {
        if (thirdPartyTransfer && recipientCardTbank) {
            return "Принимаю на Т-банк 3 лица по номеру карты (СБП нет!)";
        }
        if (thirdPartyTransfer) {
            return "Принимаю на карту 3 лица по номеру карты (СБП нет!) "
                    + "(Возможна комиссия, если у вас закончился лимит бесплатных переводов)";
        }
        if (recipientCardTbank) {
            return "Принимаю на личную карту Т-банка / карту жены, только по номеру карты (СБП нет!)";
        }
        return "Принимаю на личную карту / карту жены по номеру карты (СБП нет!) "
                + "(Возможна комиссия, если у вас закончился лимит бесплатных переводов)";
    }
}
