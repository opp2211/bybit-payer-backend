package ru.maltsev.bybitpayerbackend.common.logging;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        boolean failedWithException = false;
        MDC.put(REQUEST_ID_MDC_KEY, requestId);

        try {
            response.setHeader(REQUEST_ID_HEADER, requestId);
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failedWithException = true;
            log.error(
                    "HTTP request failed: requestId={}, method={}, path={}, exception={}, durationMs={}",
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    exception.getClass().getSimpleName(),
                    (System.nanoTime() - startedAt) / 1_000_000,
                    exception
            );
            throw exception;
        } finally {
            try {
                if (!failedWithException && response.getStatus() >= 500) {
                    log.error(
                            "HTTP request failed: requestId={}, method={}, path={}, status={}, durationMs={}",
                            requestId,
                            request.getMethod(),
                            request.getRequestURI(),
                            response.getStatus(),
                            (System.nanoTime() - startedAt) / 1_000_000
                    );
                }
            } finally {
                MDC.remove(REQUEST_ID_MDC_KEY);
            }
        }
    }
}
