package com.ishank.urlshortener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws IOException {
        filter = new RateLimitFilter(2);
        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);
        filterChain = Mockito.mock(FilterChain.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    void doFilter_allowsGetRequestsWithoutRateLimiting() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/urls/some-code");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void doFilter_neverRateLimitsHealthEndpointEvenIfPost() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/urls/health");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void doFilter_healthEndpointBypassesRateLimiting_evenAt100PlusRequests() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/urls/health");
        when(request.getRemoteAddr()).thenReturn("10.10.10.10");

        for (int i = 0; i < 120; i++) {
            filter.doFilter(request, response, filterChain);
        }

        verify(filterChain, times(120)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void doFilter_enforcesLimitAndReturnsCustomResponseBody() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/urls");
        when(request.getRemoteAddr()).thenReturn("192.168.1.50");

        // Request 1: allowed
        filter.doFilter(request, response, filterChain);
        // Request 2: allowed
        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(2)).doFilter(request, response);

        // Request 3: blocked
        HttpServletResponse blockedResponse = Mockito.mock(HttpServletResponse.class);
        StringWriter blockedWriter = new StringWriter();
        when(blockedResponse.getWriter()).thenReturn(new PrintWriter(blockedWriter));

        filter.doFilter(request, blockedResponse, filterChain);

        verify(blockedResponse).setStatus(429);
        verify(blockedResponse).setContentType("application/json");
        verify(blockedResponse).addHeader("Retry-After", "60");
        verify(blockedResponse).addHeader("X-Rate-Limit-Remaining", "0");

        String body = blockedWriter.toString();
        assertThat(body).contains("\"error\":\"Rate limit exceeded\"");
        assertThat(body).contains("\"message\":\"You have exceeded 2 requests per minute. Please slow down.\"");
        assertThat(body).contains("\"retryAfter\":60");
    }
}
