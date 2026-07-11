package com.repolens.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link TraceIdFilter}.
 * Verifies that:
 * <ul>
 *   <li>The {@code X-Trace-Id} response header is set on every request.</li>
 *   <li>MDC is cleared after the filter chain completes.</li>
 *   <li>The trace ID is exactly 8 alphanumeric hex characters.</li>
 * </ul>
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void shouldSetXTraceIdResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        String traceId = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(traceId)
                .isNotNull()
                .hasSize(8)
                .matches("[0-9a-f]+");
    }

    @Test
    void shouldClearMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Capture MDC state during filter execution via a custom chain
        String[] mdcDuringRequest = new String[1];
        FilterChain capturingChain = new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                mdcDuringRequest[0] = MDC.get(TraceIdFilter.TRACE_ID_KEY);
            }
        };

        filter.doFilterInternal(request, response, capturingChain);

        // MDC was populated during the request
        assertThat(mdcDuringRequest[0])
                .isNotNull()
                .hasSize(8);

        // MDC must be cleared after the request completes
        assertThat(MDC.get(TraceIdFilter.TRACE_ID_KEY))
                .isNull();
    }

    @Test
    void shouldGenerateUniqueTraceIdPerRequest() throws Exception {
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/repos");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, res1, new MockFilterChain());

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/repos");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, new MockFilterChain());

        String traceId1 = res1.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        String traceId2 = res2.getHeader(TraceIdFilter.TRACE_ID_HEADER);

        assertThat(traceId1).isNotEqualTo(traceId2);
    }
}
