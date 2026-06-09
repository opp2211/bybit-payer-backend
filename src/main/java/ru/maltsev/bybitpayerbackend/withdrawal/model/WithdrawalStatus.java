package ru.maltsev.bybitpayerbackend.withdrawal.model;

import lombok.Getter;

import java.util.EnumSet;
import java.util.Set;

@Getter
public enum WithdrawalStatus {
    NEW("Новая"),
    QUEUED("В очереди"),
    IN_WORK("В работе"),
    PAYMENT_IN_PROGRESS("Производится оплата"),
    PAYMENT_VERIFICATION("Верификация оплаты"),
    COMPLETED("Завершено"),
    CANCELLED("Отменено"),
    ERROR("Ошибка");

    public static final Set<WithdrawalStatus> ACTIVE_STATUSES = EnumSet.of(
            NEW,
            QUEUED,
            IN_WORK,
            PAYMENT_IN_PROGRESS,
            PAYMENT_VERIFICATION,
            ERROR
    );

    public static final Set<WithdrawalStatus> QUEUE_MANAGED_STATUSES = EnumSet.of(NEW, QUEUED, IN_WORK);
    public static final Set<WithdrawalStatus> CANCELLABLE_STATUSES = EnumSet.of(NEW, QUEUED, IN_WORK);
    public static final Set<WithdrawalStatus> RELEASABLE_STATUSES = EnumSet.of(PAYMENT_VERIFICATION, ERROR);

    private final String title;

    WithdrawalStatus(String title) {
        this.title = title;
    }

    public boolean canBeCancelled() {
        return CANCELLABLE_STATUSES.contains(this);
    }

    public boolean canBeReleased() {
        return RELEASABLE_STATUSES.contains(this);
    }
}
