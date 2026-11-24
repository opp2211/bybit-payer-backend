package ru.maltsev.bybitpayerbackend.bybit.service;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalUpdatesStream;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalUpdatesStream.ChatUpdate;

@Service
@RequiredArgsConstructor
@Slf4j
public class BybitChatRefreshJob {

    private final WithdrawalUpdatesStream updatesStream;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final BybitChatService chatService;

    private final Set<ChatUpdate> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService refresh = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().daemon(true).name("bybit-chat-refresh-", 0).factory()
    );

    @Scheduled(fixedDelay = 2_000)
    public void refreshOpenChats() {
        for (ChatUpdate chat : updatesStream.subscribedChats()) {
            if (pending.add(chat)) {
                try {
                    refresh.execute(() -> refreshChat(chat));
                } catch (RejectedExecutionException exception) {
                    pending.remove(chat);
                }
            }
        }
    }

    private void refreshChat(ChatUpdate chat) {
        try {
            if (updatesStream.isChatSubscribed(chat)) {
                withdrawalRepository.findLinkedChatWithdrawal(chat.workspaceId(), chat.withdrawalPublicId())
                        .ifPresent(withdrawal -> chatService.refreshHistory(withdrawal.getWorkspace(), withdrawal));
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh open Bybit chat: withdrawalPublicId={}", chat.withdrawalPublicId(), exception);
        } finally {
            pending.remove(chat);
        }
    }

    @PreDestroy
    void shutdown() {
        refresh.shutdownNow();
    }
}
