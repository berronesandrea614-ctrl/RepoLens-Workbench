package com.repolens.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * HTTP request filter that:
 * <ol>
 *   <li>Generates a short trace ID (first 8 chars of a UUID) for every request.</li>
 *   <li>Puts it in SLF4J MDC as {@code traceId} so logback can include it in every log line.</li>
 *   <li>Echoes it back to the caller via the {@code X-Trace-Id} response header.</li>
 *   <li>Clears the MDC entry in {@code finally} to prevent thread-pool leakage.</li>
 * </ol>
 */
@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try {
            MDC.put(TRACE_ID_KEY, traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
