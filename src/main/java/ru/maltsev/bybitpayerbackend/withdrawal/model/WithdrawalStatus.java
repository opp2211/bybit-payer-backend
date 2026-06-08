package ru.maltsev.bybitpayerbackend.withdrawal.model;

import java.util.EnumSet;
import java.util.Set;

public enum WithdrawalStatus {
    NEW("\u041d\u043e\u0432\u0430\u044f"),
    QUEUED("\u0412 \u043e\u0447\u0435\u0440\u0435\u0434\u0438"),
    IN_WORK("\u0412 \u0440\u0430\u0431\u043e\u0442\u0435"),
    PAYMENT_IN_PROGRESS("\u041f\u0440\u043e\u0438\u0437\u0432\u043e\u0434\u0438\u0442\u0441\u044f \u043e\u043f\u043b\u0430\u0442\u0430"),
    PAYMENT_VERIFICATION("\u0412\u0435\u0440\u0438\u0444\u0438\u043a\u0430\u0446\u0438\u044f \u043e\u043f\u043b\u0430\u0442\u044b"),
    COMPLETED("\u0417\u0430\u0432\u0435\u0440\u0448\u0435\u043d\u043e"),
    CANCELLED("\u041e\u0442\u043c\u0435\u043d\u0435\u043d\u043e"),
    ERROR("\u041e\u0448\u0438\u0431\u043a\u0430");

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

    private final String title;

    WithdrawalStatus(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public boolean canBeCancelled() {
        return CANCELLABLE_STATUSES.contains(this);
    }
}
