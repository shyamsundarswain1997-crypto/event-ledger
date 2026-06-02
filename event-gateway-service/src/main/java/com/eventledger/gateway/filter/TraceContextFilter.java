package com.eventledger.gateway.filter;

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
 * Generates a unique trace ID for every incoming request to the Gateway.
 *
 * <p>The trace ID is stored in SLF4J MDC so it automatically appears in all
 * log statements for the duration of the request. It is also set in the response
 * header so clients can correlate their request with Gateway/Account Service logs.</p>
 *
 * <p>The same trace ID is propagated to the Account Service via {@code X-Trace-Id}
 * in {@link com.eventledger.gateway.client.AccountServiceClient}.</p>
 */
@Slf4j
@Component
@Order(1)
public class TraceContextFilter implements Filter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_KEY = "traceId";
    public static final String MDC_SERVICE_KEY = "service";
    private static final String SERVICE_NAME = "event-gateway";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Prefer incoming trace ID (e.g., from a load balancer or test client);
        // otherwise generate a fresh one at the system boundary
        String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_TRACE_KEY, traceId);
        MDC.put(MDC_SERVICE_KEY, SERVICE_NAME);
        httpResponse.setHeader(TRACE_ID_HEADER, traceId);

        log.debug("Request received method={} uri={} traceId={}",
                httpRequest.getMethod(), httpRequest.getRequestURI(), traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_KEY);
            MDC.remove(MDC_SERVICE_KEY);
        }
    }
}
