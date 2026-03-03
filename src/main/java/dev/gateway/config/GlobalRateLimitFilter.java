package dev.gateway.config;

import dev.gateway.service.GlobalRateLimiterService;
import dev.gateway.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that applies global Redis-backed rate limiting per tenant.
 *
 * <p>Execution order: this filter is ordered at 101, which guarantees it runs
 * AFTER the Spring Security filter chain (ordered ~100), which includes
 * {@link dev.gateway.tenant.TenantFilter} and {@code BearerTokenAuthenticationFilter}.
 * Without this ordering, {@link dev.gateway.tenant.TenantContext} may not be populated
 * yet when this filter executes, causing rate limiting to be silently skipped.
 */
@Component
@Order(101)
public class GlobalRateLimitFilter extends OncePerRequestFilter {

    private final GlobalRateLimiterService rateLimiterService;

    public GlobalRateLimitFilter(GlobalRateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        if (TenantContext.isBound()) {
            String tenantId = TenantContext.getCurrentTenant().toString();
            
            if (!rateLimiterService.isAllowed(tenantId)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.getWriter().write("Too many requests. Global rate limit exceeded.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
