package com.eventledger.account.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Extracts the trace ID propagated from the Event Gateway via the {@code X-Trace-Id} header,
 * binds it to SLF4J's MDC for the duration of the request so all log statements
 * automatically include it, and echoes it back in the response.
 *
 * <p>If no trace ID is received (e.g., direct calls during testing), a new one is generated
 * so all logs remain traceable.</p>
 */
@Slf4j
@Component
@Order(1)
public class TraceContextFilter implements Filter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_KEY = "traceId";
    public static final String MDC_SERVICE_KEY = "service";
    private static final String SERVICE_NAME = "account-service";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            log.debug("No trace ID received — generated new traceId={}", traceId);
        }

        MDC.put(MDC_TRACE_KEY, traceId);
        MDC.put(MDC_SERVICE_KEY, SERVICE_NAME);
        httpResponse.setHeader(TRACE_ID_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_KEY);
            MDC.remove(MDC_SERVICE_KEY);
        }
    }
}
