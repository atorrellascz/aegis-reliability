package com.aegis.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the distributed rate limiter to the AI endpoint, keyed per tenant.

 * A 429 is returned with a Retry-After hint so well-behaved clients (and agents)
 * back off instead of retrying immediately and amplifying load. This is the
 * server side of the retry/backoff contract.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int CAPACITY = 20;       // allow a burst of 20
    private static final double REFILL_PER_SEC = 5; // sustained 5 req/s per tenant

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only guard the expensive AI path; let health/metrics through.
        return !request.getRequestURI().startsWith("/api/v1/work-items");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String tenant = request.getHeader("X-Tenant-Id");
        if (tenant == null || tenant.isBlank()) {
            tenant = "anonymous";
        }
        String key = "rl:" + tenant + ":" + request.getMethod() + ":" + request.getRequestURI();

        RateLimiter.Decision decision = rateLimiter.tryAcquire(key, CAPACITY, REFILL_PER_SEC);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limited\",\"retry_after_seconds\":1}");
            return;
        }
        chain.doFilter(request, response);
    }
}
