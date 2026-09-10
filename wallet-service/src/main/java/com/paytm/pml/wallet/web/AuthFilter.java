package com.paytm.pml.wallet.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.pml.wallet.auth.TokenRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Bearer-token auth. Runs after the correlation-id filter. Business endpoints
 * (/wallets, /transfers) require a valid token; actuator/health/metrics are open
 * so the platform can probe and scrape them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTR = "caller.user";

    private final TokenRegistry tokens;
    private final ObjectMapper mapper;

    public AuthFilter(TokenRegistry tokens, ObjectMapper mapper) {
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.equals("/metrics") || path.equals("/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<String> user = Optional.empty();
        if (header != null && header.startsWith("Bearer ")) {
            user = tokens.userForToken(header.substring("Bearer ".length()).trim());
        }
        if (user.isEmpty()) {
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(CALLER_ATTR, user.get());
        MDC.put("user", user.get());
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), Map.of(
                "code", "unauthorized",
                "message", "missing or invalid bearer token"));
    }
}
