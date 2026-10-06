package ru.maltsev.bybitpayerbackend.common.logging;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void logsServerErrorWithSameRequestIdAsResponse(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workspaces/12538F2/system/resync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            requestIdInChain.set(MDC.get("requestId"));
            ((MockHttpServletResponse) servletResponse).setStatus(500);
        });

        String requestId = response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
        assertNotNull(requestId);
        assertEquals(requestId, requestIdInChain.get());
        assertTrue(output.getOut().contains("requestId=" + requestId));
        assertTrue(output.getOut().contains("method=POST, path=/api/workspaces/12538F2/system/resync, status=500"));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void doesNotLogExpectedClientError(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workspaces/missing/system/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(404));

        assertNotNull(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER));
        assertFalse(output.getOut().contains("HTTP request failed"));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void logsThrownExceptionAndClearsRequestId(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workspaces/12538F2/system/resync");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilter(request, response,
                (servletRequest, servletResponse) -> {
                    throw new ServletException("Unexpected failure");
                }));

        String requestId = response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
        assertNotNull(requestId);
        assertTrue(output.getOut().contains("requestId=" + requestId));
        assertTrue(output.getOut().contains("exception=ServletException"));
        assertTrue(output.getOut().contains("jakarta.servlet.ServletException: Unexpected failure"));
        assertNull(MDC.get("requestId"));
    }
}
