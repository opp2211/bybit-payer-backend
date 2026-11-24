package ru.maltsev.bybitpayerbackend.withdrawal.service;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalUpdateResponse;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceMemberRepository;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;

@Service
@Slf4j
@RequiredArgsConstructor
public class WithdrawalUpdatesStream {

    private static final long STREAM_TIMEOUT_MILLIS = 120_000;
    private static final Pattern WITHDRAWAL_ID = Pattern.compile("[0-9A-F]{7}");

    private final CurrentUserService currentUserService;
    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceMemberRepository memberRepository;

    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final ExecutorService delivery = new ThreadPoolExecutor(
            2, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().daemon(true).name("withdrawal-sse-", 0).factory()
    );

    public SseEmitter subscribe(String workspacePublicId, String chatPublicId, HttpSession session) throws IOException {
        UserEntity user = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, user);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        ChatUpdate chat = chatPublicId != null && WITHDRAWAL_ID.matcher(chatPublicId).matches()
                ? new ChatUpdate(workspace.getId(), chatPublicId)
                : null;
        Subscription subscription = new Subscription(workspace.getId(), user.getId(), session, emitter, chat);
        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> disconnect(subscription));
        emitter.onError(error -> subscriptions.remove(subscription));
        emitter.send(SseEmitter.event().name("ready").reconnectTime(2_000).data(chat == null ? "ready" : "chat"));
        subscriptions.add(subscription);
        return emitter;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onWithdrawalUpdate(CommittedUpdate event) {
        for (Subscription subscription : subscriptions) {
            if (subscription.workspaceId().equals(event.workspaceId())) {
                send(subscription, SseEmitter.event().name("withdrawal").data(event.update(), MediaType.APPLICATION_JSON));
            }
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onChatUpdate(ChatUpdate update) {
        for (Subscription subscription : subscriptions) {
            if (update.equals(subscription.chat())) {
                send(subscription, SseEmitter.event().name("chat").data(update.withdrawalPublicId()));
            }
        }
    }

    public Set<ChatUpdate> subscribedChats() {
        return subscriptions.stream()
                .map(Subscription::chat)
                .filter(chat -> chat != null)
                .collect(Collectors.toSet());
    }

    public boolean isChatSubscribed(ChatUpdate chat) {
        return subscriptions.stream().anyMatch(subscription -> chat.equals(subscription.chat()));
    }

    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        for (Subscription subscription : subscriptions) {
            send(subscription, SseEmitter.event().name("heartbeat").data("ping"));
        }
    }

    private void send(Subscription subscription, SseEmitter.SseEventBuilder event) {
        try {
            delivery.execute(() -> deliver(subscription, event));
        } catch (RejectedExecutionException exception) {
            subscriptions.remove(subscription);
        }
    }

    private void deliver(Subscription subscription, SseEmitter.SseEventBuilder event) {
        if (!subscriptions.contains(subscription)) {
            return;
        }
        try {
            subscription.session().getCreationTime();
            if (!memberRepository.existsByWorkspace_IdAndUser_IdAndWorkspace_DeletedAtIsNull(
                    subscription.workspaceId(), subscription.userId()
            )) {
                disconnect(subscription);
                return;
            }
            subscription.emitter().send(event);
        } catch (IOException exception) {
            subscriptions.remove(subscription);
        } catch (RuntimeException exception) {
            log.debug("Closing withdrawal event stream: workspaceId={}", subscription.workspaceId(), exception);
            disconnect(subscription);
        }
    }

    private void disconnect(Subscription subscription) {
        if (subscriptions.remove(subscription)) {
            subscription.emitter().complete();
        }
    }

    @PreDestroy
    void shutdown() {
        subscriptions.forEach(this::disconnect);
        delivery.shutdownNow();
    }

    public record CommittedUpdate(Long workspaceId, WithdrawalUpdateResponse update) {
    }

    public record ChatUpdate(Long workspaceId, String withdrawalPublicId) {
    }

    private record Subscription(
            Long workspaceId, Long userId, HttpSession session, SseEmitter emitter, ChatUpdate chat
    ) {
    }
}
