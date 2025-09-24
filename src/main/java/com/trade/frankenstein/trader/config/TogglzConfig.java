package com.trade.frankenstein.trader.config;

import com.trade.frankenstein.trader.common.enums.BotFeature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.togglz.console.TogglzConsoleServlet;
import org.togglz.core.manager.EnumBasedFeatureProvider;
import org.togglz.core.repository.StateRepository;
import org.togglz.core.repository.file.FileBasedStateRepository;
import org.togglz.core.spi.FeatureProvider;

import java.io.File;
import java.io.IOException;

@Configuration
public class TogglzConfig {

    @Bean
    public FeatureProvider featureProvider() {
        return new EnumBasedFeatureProvider(BotFeature.class);
    }

    @Bean
    public StateRepository stateRepository() {
        // Persists flags to a properties file (checked into data dir)
        File file = new File("data/feature-flags.properties");
        file.getParentFile().mkdirs();
        return new FileBasedStateRepository(file);
    }

    /**
     * Expose the built-in console at /internal/togglz/*
     */
    @Bean
    public ServletRegistrationBean<TogglzConsoleServlet> togglzConsole() {
        ServletRegistrationBean<TogglzConsoleServlet> bean =
                new ServletRegistrationBean<>(new TogglzConsoleServlet(), "/internal/togglz/*");
        bean.setLoadOnStartup(1);
        return bean;
    }

    /**
     * Super-simple guard: allow only localhost or requests with X-TF-Admin header matching a token.
     * Keeps things secure without adding Spring Security (as per your constraint).
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> togglzConsoleGuard(
            @Value("${trade.togglz.admin-token:password}") String adminToken) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String path = req.getRequestURI();
                if (path != null && path.startsWith("/internal/togglz")) {
                    boolean fromLocalhost = "127.0.0.1".equals(req.getRemoteAddr()) || "0:0:0:0:0:0:0:1".equals(req.getRemoteAddr());
                    boolean tokenOk = adminToken != null && !adminToken.isEmpty()
                            && adminToken.equals(req.getHeader("X-TF-Admin"));
                    if (!(fromLocalhost || tokenOk)) {
                        res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                        return;
                    }
                }
                chain.doFilter(req, res);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
        bean.addUrlPatterns("/internal/togglz/*");
        bean.setOrder(1);
        return bean;
    }
}
